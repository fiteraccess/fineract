/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.accounting.journalentry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForSavings;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.data.SavingsDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsJournalEntryAllocation;
import org.apache.fineract.accounting.journalentry.data.SavingsTransactionDTO;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeCommissionAllocationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CashBasedAccountingProcessorForSavingsTest {

    private static final LocalDate TRANSACTION_DATE = LocalDate.of(2026, 7, 26);

    @Mock
    private AccountingProcessorHelper helper;
    @Mock
    private NipSwitchAccountingConfigurationProvider configurationProvider;
    @Mock
    private Office office;

    private CashBasedAccountingProcessorForSavings processor;

    @BeforeEach
    void setUp() {
        when(helper.startJournalEntryProcessingBatch()).thenReturn(mock(AccountingProcessorHelper.JournalEntryProcessingBatch.class));
        processor = new CashBasedAccountingProcessorForSavings(helper, configurationProvider);
    }

    @Nested
    class InboundNipDeposits {

        @Test
        void shouldDebitReceivableAndCreditSavingsControlForPositiveBalance() {
            GLAccount savingsControl = glAccount(101L);
            when(configurationProvider.requireInbound("NIBSS"))
                    .thenReturn(new NipSwitchAccountingConfigurationProvider.InboundConfiguration("NIBSS", 401L));
            when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                    .thenReturn(savingsControl);

            processor.createJournalEntriesForSavings(
                    savings(transaction(SavingsAccountTransactionType.DEPOSIT, "NIBSS", BigDecimal.valueOf(100), null, false)));

            assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(401L, BigDecimal.valueOf(100))),
                    List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(100))));
            verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L,
                    CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L);
        }

        @Test
        void shouldCreditOnlyOverdraftControlWhenPrincipalFullyClearsOverdraft() {
            GLAccount overdraftPortfolioControl = glAccount(102L);
            when(configurationProvider.requireInbound("NIBSS"))
                    .thenReturn(new NipSwitchAccountingConfigurationProvider.InboundConfiguration("NIBSS", 401L));
            when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                    .thenReturn(overdraftPortfolioControl);

            processor.createJournalEntriesForSavings(savings(
                    transaction(SavingsAccountTransactionType.DEPOSIT, "NIBSS", BigDecimal.valueOf(100), BigDecimal.valueOf(100), false)));

            assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(401L, BigDecimal.valueOf(100))),
                    List.of(new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(100))));
            verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L);
        }

        @Test
        void shouldSplitCreditsWhenPrincipalPartiallyClearsOverdraft() {
            GLAccount savingsControl = glAccount(101L);
            GLAccount overdraftPortfolioControl = glAccount(102L);
            when(configurationProvider.requireInbound("NIBSS"))
                    .thenReturn(new NipSwitchAccountingConfigurationProvider.InboundConfiguration("NIBSS", 401L));
            when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                    .thenReturn(savingsControl);
            when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                    .thenReturn(overdraftPortfolioControl);

            processor.createJournalEntriesForSavings(savings(
                    transaction(SavingsAccountTransactionType.DEPOSIT, "NIBSS", BigDecimal.valueOf(100), BigDecimal.valueOf(30), false)));

            assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(401L, BigDecimal.valueOf(100))),
                    List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(70)),
                            new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(30))));
        }

        @Test
        void shouldKeepLegacyDepositOnSavingsReference() {
            processor.createJournalEntriesForSavings(
                    savings(transaction(SavingsAccountTransactionType.DEPOSIT, null, BigDecimal.valueOf(100), null, false)));

            verify(helper).createCashBasedJournalEntriesAndReversalsForSavings(office, "NGN",
                    CashAccountsForSavings.SAVINGS_REFERENCE.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 22L, 44L, 11L,
                    "55", TRANSACTION_DATE, BigDecimal.valueOf(100), false, List.of());
            verifyNoInteractions(configurationProvider);
            verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                    anyList(), anyList(), eq(false), anyList());
        }

        @Test
        void shouldKeepLinkedEmtOnFinancialActivityMapping() {
            processor.createJournalEntriesForSavings(
                    savings(transaction(SavingsAccountTransactionType.EMT_LEVY, "NIBSS", BigDecimal.valueOf(50), null, false)));

            verify(helper).createCashBasedJournalEntriesAndReversalsForSavings(office, "NGN",
                    CashAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(), 22L, 44L, 11L, "55",
                    TRANSACTION_DATE, BigDecimal.valueOf(50), false, List.of());
            verifyNoInteractions(configurationProvider);
        }
    }

    @Test
    void shouldRouteNipPrincipalToSavingsControlAndConfiguredPayable() {
        GLAccount savingsControl = glAccount(101L);
        when(configurationProvider.requireOutbound("NIBSS"))
                .thenReturn(new NipSwitchAccountingConfigurationProvider.OutboundConfiguration("NIBSS", 201L, 202L, 203L));
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(principal("NIBSS", BigDecimal.valueOf(100), null, false)));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(100))),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.valueOf(100))));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(),
                44L);
    }

    @Test
    void shouldSplitNipPrincipalBetweenSavingsAndOverdraftControls() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount overdraftPortfolioControl = glAccount(102L);
        when(configurationProvider.requireOutbound("NIBSS"))
                .thenReturn(new NipSwitchAccountingConfigurationProvider.OutboundConfiguration("NIBSS", 201L, 202L, 203L));
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(savings(principal("NIBSS", BigDecimal.valueOf(100), BigDecimal.valueOf(30), false)));

        assertBalancedAllocations(
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(70)),
                        new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(30))),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.valueOf(100))));
    }

    @Test
    void shouldOmitZeroCustomerFundedLegForFullyOverdrawnNipPrincipal() {
        GLAccount overdraftPortfolioControl = glAccount(102L);
        when(configurationProvider.requireOutbound("NIBSS"))
                .thenReturn(new NipSwitchAccountingConfigurationProvider.OutboundConfiguration("NIBSS", 201L, 202L, 203L));
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(savings(principal("NIBSS", BigDecimal.valueOf(100), BigDecimal.valueOf(100), false)));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(100))),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.valueOf(100))));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L);
    }

    @Test
    void shouldPreserveLegacyWithdrawalRoutingWithoutResolvingSwitchConfiguration() {
        SavingsTransactionDTO transaction = principal(null, BigDecimal.valueOf(100), null, false);

        processor.createJournalEntriesForSavings(savings(transaction));

        verify(helper).createCashBasedJournalEntriesAndReversalsForSavings(office, "NGN", CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                CashAccountsForSavings.SAVINGS_REFERENCE.getValue(), 22L, 44L, 11L, "55", TRANSACTION_DATE, BigDecimal.valueOf(100), false,
                List.of());
        verifyNoInteractions(configurationProvider);
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                anyList(), anyList(), eq(false), anyList());
    }

    @Test
    void shouldRouteCustomerFundedCommissionUsingExactlySuppliedPositiveLegs() {
        GLAccount savingsControl = glAccount(101L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(commission(BigDecimal.TEN, null, BigDecimal.valueOf(3), BigDecimal.valueOf(7))));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.valueOf(3)),
                        new SavingsJournalEntryAllocation(203L, BigDecimal.valueOf(7))));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L);
    }

    @Test
    void shouldOmitZeroSwitchFeeCommissionLeg() {
        GLAccount savingsControl = glAccount(101L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(commission(BigDecimal.TEN, null, BigDecimal.ZERO, BigDecimal.TEN)));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(203L, BigDecimal.TEN)));
    }

    @Test
    void shouldOmitZeroBankCommissionLeg() {
        GLAccount savingsControl = glAccount(101L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(commission(BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.ZERO)));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.TEN)));
    }

    @Test
    void shouldSplitCommissionBetweenSavingsAndOverdraftControls() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount overdraftPortfolioControl = glAccount(102L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(
                savings(commission(BigDecimal.TEN, BigDecimal.valueOf(4), BigDecimal.valueOf(3), BigDecimal.valueOf(7))));

        assertBalancedAllocations(
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(6)),
                        new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(4))),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.valueOf(3)),
                        new SavingsJournalEntryAllocation(203L, BigDecimal.valueOf(7))));
    }

    @Test
    void shouldRouteFullyOverdrawnCommissionWithoutSavingsControlLeg() {
        GLAccount overdraftPortfolioControl = glAccount(102L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(
                savings(commission(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(3), BigDecimal.valueOf(7))));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(102L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.valueOf(3)),
                        new SavingsJournalEntryAllocation(203L, BigDecimal.valueOf(7))));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L);
    }

    @Test
    void shouldRejectMissingCommissionAllocationBeforeConfigurationOrJournalWork() {
        SavingsTransactionDTO commission = transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN, null, false);

        assertThatThrownBy(() -> processor.createJournalEntriesForSavings(savings(commission))).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Commission accounting requires both supplied allocation legs");

        assertNoCommissionJournalWork();
    }

    @Test
    void shouldRejectMismatchedCommissionAllocationBeforeConfigurationOrJournalWork() {
        SavingsTransactionDTO commission = commission(BigDecimal.TEN, null, BigDecimal.ONE, BigDecimal.TWO);

        assertThatThrownBy(() -> processor.createJournalEntriesForSavings(savings(commission))).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("must equal the Commission transaction amount");

        assertNoCommissionJournalWork();
    }

    @Test
    void shouldRejectNegativeCommissionAllocationBeforeConfigurationOrJournalWork() {
        SavingsTransactionDTO commission = commission(BigDecimal.TEN, null, BigDecimal.valueOf(-1), BigDecimal.valueOf(11));

        assertThatThrownBy(() -> processor.createJournalEntriesForSavings(savings(commission))).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("must be non-negative");

        assertNoCommissionJournalWork();
    }

    @Test
    void shouldRouteCustomerFundedVatToMappedPayableUsingExactCallerAmount() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount vatPayable = glAccount(301L);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(vat(BigDecimal.valueOf(6), null)));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(6))),
                List.of(new SavingsJournalEntryAllocation(301L, BigDecimal.valueOf(6))));
        verifyNoInteractions(configurationProvider);
        assertThat(mockingDetails(helper).getInvocations())
                .noneMatch(invocation -> invocation.getMethod().getName().contains("JournalEntriesAndReversalsForSavingsTax"));
    }

    @Test
    void shouldSplitVatBetweenSavingsAndOverdraftControls() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount overdraftPortfolioControl = glAccount(102L);
        GLAccount vatPayable = glAccount(301L);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(savings(vat(BigDecimal.valueOf(6), BigDecimal.valueOf(2))));

        assertBalancedAllocations(
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(4)),
                        new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(2))),
                List.of(new SavingsJournalEntryAllocation(301L, BigDecimal.valueOf(6))));
    }

    @Test
    void shouldRouteFullyOverdrawnVatWithoutSavingsControlLeg() {
        GLAccount overdraftPortfolioControl = glAccount(102L);
        GLAccount vatPayable = glAccount(301L);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(savings(vat(BigDecimal.valueOf(6), BigDecimal.valueOf(6))));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(6))),
                List.of(new SavingsJournalEntryAllocation(301L, BigDecimal.valueOf(6))));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L);
    }

    @Test
    void shouldNotAppendOrPersistVatJournalEntriesWhenVatPayableMappingFails() {
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L))
                .thenThrow(new IllegalStateException("VAT_PAYABLE unavailable"));

        assertThatThrownBy(() -> processor.createJournalEntriesForSavings(savings(vat(BigDecimal.valueOf(6), null))))
                .isInstanceOf(IllegalStateException.class).hasMessage("VAT_PAYABLE unavailable");

        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L), eq(CashAccountsForSavings.SAVINGS_CONTROL.getValue()), eq(44L));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L),
                eq(CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue()), eq(44L));
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                anyList(), anyList(), eq(false), anyList());
        verify(helper, never()).persistJournalEntries(anyList());
    }

    @Test
    void shouldNotResolveVatPayableForFeeFreeRequestWithoutVatTransactions() {
        processor.createJournalEntriesForSavings(new SavingsDTO(11L, 22L, 33L, "NGN", true, false, List.of(), office));

        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L);
        verifyNoInteractions(configurationProvider);
    }

    @Test
    void shouldNotCreateOrPersistJournalEntriesWhenSwitchConfigurationFails() {
        when(configurationProvider.requireOutbound("NIBSS")).thenThrow(new IllegalStateException("configuration unavailable"));

        assertThatThrownBy(
                () -> processor.createJournalEntriesForSavings(savings(principal("NIBSS", BigDecimal.valueOf(100), null, false))))
                .isInstanceOf(IllegalStateException.class).hasMessage("configuration unavailable");

        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L), eq(CashAccountsForSavings.SAVINGS_CONTROL.getValue()), eq(44L));
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                anyList(), anyList(), eq(false), anyList());
        verify(helper, never()).persistJournalEntries(anyList());
    }

    @Test
    void shouldUseEachNipSavingsTransactionIdentifierForItsOwnJournalEntries() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount vatPayable = glAccount(301L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, CashAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        SavingsTransactionDTO principal = transaction(SavingsAccountTransactionType.WITHDRAWAL, "NIBSS", BigDecimal.valueOf(100), null,
                false, null, "55");
        SavingsTransactionDTO commission = transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN, null, false,
                new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.valueOf(3), BigDecimal.valueOf(7)), "56");
        SavingsTransactionDTO vat = transaction(SavingsAccountTransactionType.VAT, "NIBSS", BigDecimal.valueOf(6), null, false, null, "57");

        processor.createJournalEntriesForSavings(savings(principal, commission, vat));

        ArgumentCaptor<String> transactionIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(helper, times(3)).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), transactionIdCaptor.capture(),
                eq(TRANSACTION_DATE), anyList(), anyList(), eq(false), anyList());
        assertThat(transactionIdCaptor.getAllValues()).containsExactly("55", "56", "57");
    }

    private SavingsDTO savings(final SavingsTransactionDTO transaction) {
        return new SavingsDTO(11L, 22L, 33L, "NGN", true, false, List.of(transaction), office);
    }

    private SavingsDTO savings(final SavingsTransactionDTO... transactions) {
        return new SavingsDTO(11L, 22L, 33L, "NGN", true, false, List.of(transactions), office);
    }

    private SavingsTransactionDTO principal(final String switchId, final BigDecimal amount, final BigDecimal overdraftAmount,
            final boolean accountTransfer) {
        return transaction(SavingsAccountTransactionType.WITHDRAWAL, switchId, amount, overdraftAmount, accountTransfer);
    }

    private SavingsTransactionDTO commission(final BigDecimal amount, final BigDecimal overdraftAmount, final BigDecimal switchFeeAmount,
            final BigDecimal bankCommissionAmount) {
        return transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", amount, overdraftAmount, false,
                new SavingsAccountingBridgeCommissionAllocationDTO(switchFeeAmount, bankCommissionAmount));
    }

    private SavingsTransactionDTO vat(final BigDecimal amount, final BigDecimal overdraftAmount) {
        return transaction(SavingsAccountTransactionType.VAT, "NIBSS", amount, overdraftAmount, false);
    }

    private SavingsTransactionDTO transaction(final SavingsAccountTransactionType type, final String switchId, final BigDecimal amount,
            final BigDecimal overdraftAmount, final boolean accountTransfer) {
        return transaction(type, switchId, amount, overdraftAmount, accountTransfer, null);
    }

    private SavingsTransactionDTO transaction(final SavingsAccountTransactionType type, final String switchId, final BigDecimal amount,
            final BigDecimal overdraftAmount, final boolean accountTransfer,
            final SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation) {
        return transaction(type, switchId, amount, overdraftAmount, accountTransfer, commissionAllocation, "55");
    }

    private SavingsTransactionDTO transaction(final SavingsAccountTransactionType type, final String switchId, final BigDecimal amount,
            final BigDecimal overdraftAmount, final boolean accountTransfer,
            final SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation, final String transactionId) {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(Long.valueOf(type.getValue()),
                type.getCode(), type.name());
        return new SavingsTransactionDTO(33L, 44L, transactionId, TRANSACTION_DATE, transactionType, amount, false, List.of(), List.of(),
                overdraftAmount, accountTransfer, List.of(), switchId, commissionAllocation);
    }

    private void configureSwitch() {
        when(configurationProvider.requireOutbound("NIBSS"))
                .thenReturn(new NipSwitchAccountingConfigurationProvider.OutboundConfiguration("NIBSS", 201L, 202L, 203L));
    }

    private void assertNoCommissionJournalWork() {
        verifyNoInteractions(configurationProvider);
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L), eq(CashAccountsForSavings.SAVINGS_CONTROL.getValue()),
                eq(44L));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L),
                eq(CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue()), eq(44L));
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                anyList(), anyList(), eq(false), anyList());
        verify(helper, never()).persistJournalEntries(anyList());
    }

    private GLAccount glAccount(final Long id) {
        GLAccount account = mock(GLAccount.class);
        when(account.getId()).thenReturn(id);
        return account;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void assertBalancedAllocations(final List<SavingsJournalEntryAllocation> expectedDebits,
            final List<SavingsJournalEntryAllocation> expectedCredits) {
        ArgumentCaptor<List<SavingsJournalEntryAllocation>> debitCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<List<SavingsJournalEntryAllocation>> creditCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(helper).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                debitCaptor.capture(), creditCaptor.capture(), eq(false), anyList());
        assertThat(debitCaptor.getValue()).containsExactlyElementsOf(expectedDebits);
        assertThat(creditCaptor.getValue()).containsExactlyElementsOf(expectedCredits);
    }
}
