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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.journalentry.LoanJournalEntryCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeChargePaymentDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTaxDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTransactionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    void persistJournalEntriesShouldBatchSaveAndNotifyForNewLoanEntries() {
        JournalEntry loanJournalEntry = journalEntry(101L);
        JournalEntry savingsJournalEntry = journalEntry(null);
        List<JournalEntry> journalEntries = List.of(loanJournalEntry, savingsJournalEntry);
        when(glJournalEntryRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<JournalEntry> savedJournalEntries = underTest.persistJournalEntries(journalEntries);

        assertThat(savedJournalEntries).containsExactlyElementsOf(journalEntries);
        verify(glJournalEntryRepository).saveAll(journalEntries);
        verify(glJournalEntryRepository, never()).saveAndFlush(any(JournalEntry.class));
        ArgumentCaptor<LoanJournalEntryCreatedBusinessEvent> eventCaptor = ArgumentCaptor
                .forClass(LoanJournalEntryCreatedBusinessEvent.class);
        verify(businessEventNotifierService).notifyPostBusinessEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().get()).isSameAs(loanJournalEntry);
    }

    @Test
    void persistJournalEntryShouldUseBatchSaveForSingleEntry() {
        JournalEntry journalEntry = journalEntry(202L);
        when(glJournalEntryRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        JournalEntry savedJournalEntry = underTest.persistJournalEntry(journalEntry);

        assertThat(savedJournalEntry).isSameAs(journalEntry);
        verify(glJournalEntryRepository).saveAll(List.of(journalEntry));
        verify(glJournalEntryRepository, never()).saveAndFlush(any(JournalEntry.class));
        verify(businessEventNotifierService).notifyPostBusinessEvent(any(LoanJournalEntryCreatedBusinessEvent.class));
    }

    @Test
    void populateSavingsDtoFromDTOShouldConvertTypedBridgeData() {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(
                Long.valueOf(SavingsAccountTransactionType.WITHHOLD_TAX.getValue()), "withholdTax", "Withhold tax");
        SavingsAccountingBridgeTransactionDTO transactionDTO = new SavingsAccountingBridgeTransactionDTO(55L, 66L, transactionType, false,
                LocalDate.of(2026, 2, 3), "USD", BigDecimal.TEN, BigDecimal.ONE, 77L,
                new ArrayList<>(List.of(new SavingsAccountingBridgeChargePaymentDTO(88L, 99L, true, BigDecimal.TWO),
                        new SavingsAccountingBridgeChargePaymentDTO(111L, 222L, false, BigDecimal.valueOf(3)))),
                new ArrayList<>(List.of(new SavingsAccountingBridgeTaxDTO(BigDecimal.valueOf(4), 333L, 444L))));
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
            assertThat(savingsTransactionDTO.getPenaltyPayments()).singleElement().extracting("chargeId", "loanChargeId", "amount")
                    .containsExactly(88L, 99L, BigDecimal.TWO);
            assertThat(savingsTransactionDTO.getFeePayments()).singleElement().extracting("chargeId", "loanChargeId", "amount")
                    .containsExactly(111L, 222L, BigDecimal.valueOf(3));
            assertThat(savingsTransactionDTO.getTaxPayments()).singleElement().extracting("debitAccountId", "creditAccountId", "amount")
                    .containsExactly(333L, 444L, BigDecimal.valueOf(4));
        });
        verify(accountTransfersReadPlatformService).isAccountTransfer(55L, PortfolioAccountType.SAVINGS);
    }

    private JournalEntry journalEntry(Long loanTransactionId) {
        return JournalEntry.createNew(org.mockito.Mockito.mock(Office.class), null, org.mockito.Mockito.mock(GLAccount.class), "USD",
                "txn-id", false, LocalDate.of(2026, 1, 1), JournalEntryType.DEBIT, BigDecimal.ONE, "note", null, null, null,
                loanTransactionId, null, null, null);
    }
}
