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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.journalentry.data.SavingsDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsJournalEntryAllocation;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.accounting.journalentry.exception.JournalEntryInvalidException;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeChargePaymentDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeCommissionAllocationDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTaxDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTransactionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountingProcessorHelperTest {

    @Mock
    private JournalEntryRepository glJournalEntryRepository;
    @Mock
    private ProductToGLAccountMappingRepository accountMappingRepository;
    @Mock
    private FinancialActivityAccountRepositoryWrapper financialActivityAccountRepository;
    @Mock
    private GLClosureRepository closureRepository;
    @Mock
    private GLAccountRepository glAccountRepository;
    @Mock
    private OfficeRepository officeRepository;
    @Mock
    private AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    @Mock
    private ChargeRepositoryWrapper chargeRepositoryWrapper;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    private AccountingProcessorHelper underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 1))));
        underTest = new AccountingProcessorHelper(glJournalEntryRepository, accountMappingRepository, financialActivityAccountRepository,
                closureRepository, glAccountRepository, officeRepository, accountTransfersReadPlatformService, chargeRepositoryWrapper,
                businessEventNotifierService);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void populateSavingsDtoFromDTOShouldConvertTypedBridgeData() {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(
                Long.valueOf(SavingsAccountTransactionType.WITHHOLD_TAX.getValue()), "withholdTax", "Withhold tax");
        SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation = new SavingsAccountingBridgeCommissionAllocationDTO(
                BigDecimal.valueOf(1.25), BigDecimal.valueOf(8.75));
        SavingsAccountingBridgeTransactionDTO transactionDTO = new SavingsAccountingBridgeTransactionDTO(55L, 66L, transactionType, false,
                LocalDate.of(2026, 2, 3), "USD", BigDecimal.TEN, BigDecimal.ONE, 77L,
                new ArrayList<>(List.of(new SavingsAccountingBridgeChargePaymentDTO(88L, 99L, true, BigDecimal.TWO),
                        new SavingsAccountingBridgeChargePaymentDTO(111L, 222L, false, BigDecimal.valueOf(3)))),
                new ArrayList<>(List.of(new SavingsAccountingBridgeTaxDTO(BigDecimal.valueOf(4), 333L, 444L))), "NIBSS", null,
                commissionAllocation);
        SavingsAccountingBridgeDTO accountingBridgeData = new SavingsAccountingBridgeDTO(11L, 22L, 33L, "USD", true, false, false,
                List.of(transactionDTO));
        when(accountTransfersReadPlatformService.isAccountTransfer(55L, PortfolioAccountType.SAVINGS)).thenReturn(true);

        SavingsDTO result = underTest.populateSavingsDtoFromDTO(accountingBridgeData);

        assertThat(result.getSavingsId()).isEqualTo(11L);
        assertThat(result.getSavingsProductId()).isEqualTo(22L);
        assertThat(result.getOfficeId()).isEqualTo(33L);
        assertThat(result.getCurrencyCode()).isEqualTo("USD");
        assertThat(result.isCashBasedAccountingEnabled()).isTrue();
        assertThat(result.isAccrualBasedAccountingEnabled()).isFalse();
        assertThat(result.getNewSavingsTransactions()).singleElement().satisfies(savingsTransactionDTO -> {
            assertThat(savingsTransactionDTO.getTransactionId()).isEqualTo("55");
            assertThat(savingsTransactionDTO.getOfficeId()).isEqualTo(66L);
            assertThat(savingsTransactionDTO.getAmount()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(savingsTransactionDTO.getOverdraftAmount()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(savingsTransactionDTO.getPaymentTypeId()).isEqualTo(77L);
            assertThat(savingsTransactionDTO.isAccountTransfer()).isTrue();
            assertThat(savingsTransactionDTO.getSwitchId()).isEqualTo("NIBSS");
            assertThat(savingsTransactionDTO.getCommissionAllocation()).isEqualTo(commissionAllocation);
            assertThat(savingsTransactionDTO.getPenaltyPayments()).singleElement().extracting("chargeId", "loanChargeId", "amount")
                    .containsExactly(88L, 99L, BigDecimal.TWO);
            assertThat(savingsTransactionDTO.getFeePayments()).singleElement().extracting("chargeId", "loanChargeId", "amount")
                    .containsExactly(111L, 222L, BigDecimal.valueOf(3));
            assertThat(savingsTransactionDTO.getTaxPayments()).singleElement().extracting("debitAccountId", "creditAccountId", "amount")
                    .containsExactly(333L, 444L, BigDecimal.valueOf(4));
        });
        verify(accountTransfersReadPlatformService).isAccountTransfer(55L, PortfolioAccountType.SAVINGS);
    }

    @Test
    void populateSavingsDtoFromDTOShouldKeepLegacyNipFieldsNull() {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(
                Long.valueOf(SavingsAccountTransactionType.WITHDRAWAL.getValue()), "withdrawal", "Withdrawal");
        SavingsAccountingBridgeTransactionDTO transactionDTO = new SavingsAccountingBridgeTransactionDTO(55L, 66L, transactionType, false,
                LocalDate.of(2026, 2, 3), "USD", BigDecimal.TEN, BigDecimal.ZERO, null, new ArrayList<>(), new ArrayList<>(), null, null,
                null);
        SavingsAccountingBridgeDTO accountingBridgeData = new SavingsAccountingBridgeDTO(11L, 22L, 33L, "USD", true, false, true,
                List.of(transactionDTO));

        SavingsDTO result = underTest.populateSavingsDtoFromDTO(accountingBridgeData);

        assertThat(result.getNewSavingsTransactions()).singleElement().satisfies(savingsTransactionDTO -> {
            assertThat(savingsTransactionDTO.getSwitchId()).isNull();
            assertThat(savingsTransactionDTO.getCommissionAllocation()).isNull();
        });
        verifyNoInteractions(accountTransfersReadPlatformService);
    }

    @Test
    void createBalancedJournalEntriesForSavingsShouldPersistCompleteMultiCreditAllocation() {
        Office office = mock(Office.class);
        GLAccount debitAccount = mock(GLAccount.class);
        GLAccount switchFeeAccount = mock(GLAccount.class);
        GLAccount commissionIncomeAccount = mock(GLAccount.class);
        when(glAccountRepository.getReferenceById(101L)).thenReturn(debitAccount);
        when(glAccountRepository.getReferenceById(201L)).thenReturn(switchFeeAccount);
        when(glAccountRepository.getReferenceById(202L)).thenReturn(commissionIncomeAccount);
        when(glJournalEntryRepository.saveAndFlush(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<JournalEntry> result = underTest.createBalancedJournalEntriesForSavings(office, "NGN", 44L, "55", LocalDate.of(2026, 2, 3),
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.valueOf(2)),
                        new SavingsJournalEntryAllocation(202L, BigDecimal.valueOf(8))),
                false);

        assertThat(result).extracting(JournalEntry::getType).containsExactly(JournalEntryType.DEBIT.getValue(),
                JournalEntryType.CREDIT.getValue(), JournalEntryType.CREDIT.getValue());
        assertThat(result).extracting(JournalEntry::getGlAccount).containsExactly(debitAccount, switchFeeAccount, commissionIncomeAccount);
        assertThat(result).extracting(JournalEntry::getAmount).containsExactly(BigDecimal.TEN, BigDecimal.valueOf(2),
                BigDecimal.valueOf(8));
        assertThat(result).extracting(JournalEntry::getTransactionId).containsOnly("S55");
        assertThat(result).extracting(JournalEntry::getSavingsTransactionId).containsOnly(55L);
        verify(glJournalEntryRepository, times(3)).saveAndFlush(any(JournalEntry.class));
    }

    @Test
    void createBalancedJournalEntriesForSavingsShouldOmitZeroAllocationsBeforeAccountResolution() {
        Office office = mock(Office.class);
        GLAccount debitAccount = mock(GLAccount.class);
        GLAccount commissionIncomeAccount = mock(GLAccount.class);
        when(glAccountRepository.getReferenceById(101L)).thenReturn(debitAccount);
        when(glAccountRepository.getReferenceById(202L)).thenReturn(commissionIncomeAccount);
        when(glJournalEntryRepository.saveAndFlush(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<JournalEntry> result = underTest.createBalancedJournalEntriesForSavings(office, "NGN", 44L, "55", LocalDate.of(2026, 2, 3),
                List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.ZERO), new SavingsJournalEntryAllocation(202L, BigDecimal.TEN)),
                false);

        assertThat(result).extracting(JournalEntry::getGlAccount).containsExactly(debitAccount, commissionIncomeAccount);
        assertThat(result).extracting(JournalEntry::getAmount).containsExactly(BigDecimal.TEN, BigDecimal.TEN);
        verify(glAccountRepository, never()).getReferenceById(201L);
        verify(glJournalEntryRepository, times(2)).saveAndFlush(any(JournalEntry.class));
    }

    @Test
    void createBalancedJournalEntriesForSavingsShouldRejectMismatchBeforeResolvingOrPersisting() {
        assertThatThrownBy(() -> underTest.createBalancedJournalEntriesForSavings(mock(Office.class), "NGN", 44L, "55",
                LocalDate.of(2026, 2, 3), List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.ONE)), false)).isInstanceOf(JournalEntryInvalidException.class)
                .hasMessageContaining("Sum of All Debits must equal the sum of all Credits");

        verifyNoInteractions(glAccountRepository, glJournalEntryRepository);
    }

    @Test
    void createBalancedJournalEntriesForSavingsShouldNotMutateSharedListWhenAccountResolutionFails() {
        GLAccount debitAccount = mock(GLAccount.class);
        when(glAccountRepository.getReferenceById(101L)).thenReturn(debitAccount);
        when(glAccountRepository.getReferenceById(201L)).thenThrow(new IllegalStateException("GL unavailable"));
        List<JournalEntry> sharedJournalEntries = new ArrayList<>();

        assertThatThrownBy(() -> underTest.createBalancedJournalEntriesForSavings(mock(Office.class), "NGN", 44L, "55",
                LocalDate.of(2026, 2, 3), List.of(new SavingsJournalEntryAllocation(101L, BigDecimal.TEN)),
                List.of(new SavingsJournalEntryAllocation(201L, BigDecimal.TEN)), false, sharedJournalEntries))
                .isInstanceOf(IllegalStateException.class).hasMessage("GL unavailable");

        assertThat(sharedJournalEntries).isEmpty();
        verifyNoInteractions(glJournalEntryRepository);
    }
}
