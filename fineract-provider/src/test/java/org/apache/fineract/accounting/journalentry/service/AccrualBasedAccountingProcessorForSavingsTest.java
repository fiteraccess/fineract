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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.accounting.common.AccountingConstants.AccrualAccountsForSavings;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccrualBasedAccountingProcessorForSavingsTest {

    private static final LocalDate TRANSACTION_DATE = LocalDate.of(2026, 7, 26);

    @Mock
    private AccountingProcessorHelper helper;
    @Mock
    private NipSwitchAccountingConfigurationProvider configurationProvider;
    @Mock
    private Office office;

    private AccrualBasedAccountingProcessorForSavings processor;

    @BeforeEach
    void setUp() {
        when(helper.startJournalEntryProcessingBatch()).thenReturn(mock(AccountingProcessorHelper.JournalEntryProcessingBatch.class));
        processor = new AccrualBasedAccountingProcessorForSavings(helper, configurationProvider);
    }

    @Test
    void shouldRouteNipPrincipalWithTheSameSavingsAndOverdraftSplitAsCashAccounting() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount overdraftPortfolioControl = glAccount(102L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(savings(
                transaction(SavingsAccountTransactionType.WITHDRAWAL, "NIBSS", BigDecimal.valueOf(100), BigDecimal.valueOf(30), null)));

        assertBalancedAllocations(
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(70)),
                        new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(30))),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.valueOf(100))));
    }

    @Test
    void shouldRouteCommissionUsingTheSuppliedAllocationInAccrualAccounting() {
        GLAccount savingsControl = glAccount(101L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN,
                null, new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.valueOf(3), BigDecimal.valueOf(7)))));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.valueOf(3)),
                        new SavingsJournalEntryAllocation(203L, BigDecimal.valueOf(7))));
    }

    @Test
    void shouldOmitZeroSwitchFeeCommissionLegInAccrualAccounting() {
        GLAccount savingsControl = glAccount(101L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN,
                null, new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.ZERO, BigDecimal.TEN))));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(203L, BigDecimal.TEN)));
    }

    @Test
    void shouldOmitZeroBankCommissionLegInAccrualAccounting() {
        GLAccount savingsControl = glAccount(101L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(savings(transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN,
                null, new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.TEN, BigDecimal.ZERO))));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.TEN)));
    }

    @Test
    void shouldRejectMismatchedCommissionAllocationInAccrualAccounting() {
        SavingsTransactionDTO commission = transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN, null,
                new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.ONE, BigDecimal.TWO));

        assertThatThrownBy(() -> processor.createJournalEntriesForSavings(savings(commission))).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("must equal the Commission transaction amount");

        verifyNoInteractions(configurationProvider);
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                anyList(), anyList(), eq(false));
        verify(helper, never()).persistJournalEntries(anyList());
    }

    @Test
    void shouldSplitCommissionBetweenSavingsAndOverdraftControlsInAccrualAccounting() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount overdraftPortfolioControl = glAccount(102L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(savings(transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN,
                BigDecimal.valueOf(4), new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.valueOf(3), BigDecimal.valueOf(7)))));

        assertBalancedAllocations(
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(6)),
                        new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(4))),
                List.of(new SavingsJournalEntryAllocation(202L, BigDecimal.valueOf(3)),
                        new SavingsJournalEntryAllocation(203L, BigDecimal.valueOf(7))));
    }

    @Test
    void shouldRouteVatToTheTenantFinancialActivityMappingInAccrualAccounting() {
        GLAccount vatPayable = glAccount(301L);
        GLAccount savingsControl = glAccount(101L);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);

        processor.createJournalEntriesForSavings(
                savings(transaction(SavingsAccountTransactionType.VAT, "NIBSS", BigDecimal.valueOf(6), null, null)));

        assertBalancedAllocations(List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(6))),
                List.of(new SavingsJournalEntryAllocation(301L, BigDecimal.valueOf(6))));
        verifyNoInteractions(configurationProvider);
    }

    @Test
    void shouldSplitVatBetweenSavingsAndOverdraftControlsInAccrualAccounting() {
        GLAccount vatPayable = glAccount(301L);
        GLAccount savingsControl = glAccount(101L);
        GLAccount overdraftPortfolioControl = glAccount(102L);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), 44L))
                .thenReturn(overdraftPortfolioControl);

        processor.createJournalEntriesForSavings(
                savings(transaction(SavingsAccountTransactionType.VAT, "NIBSS", BigDecimal.valueOf(6), BigDecimal.valueOf(2), null)));

        assertBalancedAllocations(
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.valueOf(4)),
                        new SavingsJournalEntryAllocation(102L, BigDecimal.valueOf(2))),
                List.of(new SavingsJournalEntryAllocation(301L, BigDecimal.valueOf(6))));
        verifyNoInteractions(configurationProvider);
    }

    @Test
    void shouldNotCreateVatJournalEntriesWhenVatPayableMappingFailsInAccrualAccounting() {
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L))
                .thenThrow(new IllegalStateException("VAT_PAYABLE unavailable"));

        assertThatThrownBy(() -> processor.createJournalEntriesForSavings(
                savings(transaction(SavingsAccountTransactionType.VAT, "NIBSS", BigDecimal.valueOf(6), null, null))))
                .isInstanceOf(IllegalStateException.class).hasMessage("VAT_PAYABLE unavailable");

        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L), eq(AccrualAccountsForSavings.SAVINGS_CONTROL.getValue()),
                eq(44L));
        verify(helper, never()).getLinkedGLAccountForSavingsProduct(eq(22L),
                eq(AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue()), eq(44L));
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                anyList(), anyList(), eq(false));
        verify(helper, never()).persistJournalEntries(anyList());
    }

    @Test
    void shouldNotResolveVatPayableForFeeFreeRequestInAccrualAccounting() {
        processor.createJournalEntriesForSavings(new SavingsDTO(11L, 22L, 33L, "NGN", true, false, List.of(), office));

        verify(helper, never()).getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L);
        verifyNoInteractions(configurationProvider);
    }

    @Test
    void shouldUseEachNipSavingsTransactionIdentifierForItsOwnAccrualJournalEntries() {
        GLAccount savingsControl = glAccount(101L);
        GLAccount vatPayable = glAccount(301L);
        configureSwitch();
        when(helper.getLinkedGLAccountForSavingsProduct(22L, AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), 44L))
                .thenReturn(savingsControl);
        when(helper.getLinkedGLAccountForSavingsProduct(22L, FinancialActivity.VAT_PAYABLE.getValue(), 44L)).thenReturn(vatPayable);
        SavingsTransactionDTO principal = transaction(SavingsAccountTransactionType.WITHDRAWAL, "NIBSS", BigDecimal.valueOf(100), null,
                null, "55");
        SavingsTransactionDTO commission = transaction(SavingsAccountTransactionType.COMMISSION, "NIBSS", BigDecimal.TEN, null,
                new SavingsAccountingBridgeCommissionAllocationDTO(BigDecimal.valueOf(3), BigDecimal.valueOf(7)), "56");
        SavingsTransactionDTO vat = transaction(SavingsAccountTransactionType.VAT, "NIBSS", BigDecimal.valueOf(6), null, null, "57");

        processor.createJournalEntriesForSavings(savings(principal, commission, vat));

        ArgumentCaptor<String> transactionIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(helper, times(3)).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), transactionIdCaptor.capture(),
                eq(TRANSACTION_DATE), anyList(), anyList(), eq(false));
        assertThat(transactionIdCaptor.getAllValues()).containsExactly("55", "56", "57");
    }

    @Test
    void shouldLeaveExistingAccrualProductFeeAccountingUnchanged() {
        SavingsTransactionDTO productFee = transaction(SavingsAccountTransactionType.WITHDRAWAL_FEE, null, BigDecimal.valueOf(5), null,
                null);

        processor.createJournalEntriesForSavings(savings(productFee));

        verify(helper).createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, "NGN",
                AccrualAccountsForSavings.SAVINGS_CONTROL, AccrualAccountsForSavings.INCOME_FROM_FEES, 22L, 44L, 11L, "55",
                TRANSACTION_DATE, BigDecimal.valueOf(5), false, List.of());
        verifyNoInteractions(configurationProvider);
        verify(helper, never()).createBalancedJournalEntriesForSavings(eq(office), eq("NGN"), eq(11L), eq("55"), eq(TRANSACTION_DATE),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(), eq(false));
    }

    private SavingsDTO savings(final SavingsTransactionDTO transaction) {
        return new SavingsDTO(11L, 22L, 33L, "NGN", true, false, List.of(transaction), office);
    }

    private SavingsDTO savings(final SavingsTransactionDTO... transactions) {
        return new SavingsDTO(11L, 22L, 33L, "NGN", true, false, List.of(transactions), office);
    }

    private SavingsTransactionDTO transaction(final SavingsAccountTransactionType type, final String switchId, final BigDecimal amount,
            final BigDecimal overdraftAmount, final SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation) {
        return transaction(type, switchId, amount, overdraftAmount, commissionAllocation, "55");
    }

    private SavingsTransactionDTO transaction(final SavingsAccountTransactionType type, final String switchId, final BigDecimal amount,
            final BigDecimal overdraftAmount, final SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation,
            final String transactionId) {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(Long.valueOf(type.getValue()),
                type.getCode(), type.name());
        return new SavingsTransactionDTO(33L, 44L, transactionId, TRANSACTION_DATE, transactionType, amount, false, List.of(), List.of(),
                overdraftAmount, false, List.of(), switchId, commissionAllocation);
    }

    private void configureSwitch() {
        when(configurationProvider.requireOutbound("NIBSS"))
                .thenReturn(new NipSwitchAccountingConfigurationProvider.OutboundConfiguration("NIBSS", 201L, 202L, 203L));
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
                debitCaptor.capture(), creditCaptor.capture(), eq(false));
        assertThat(debitCaptor.getValue()).containsExactlyElementsOf(expectedDebits);
        assertThat(creditCaptor.getValue()).containsExactlyElementsOf(expectedCredits);
    }
}
