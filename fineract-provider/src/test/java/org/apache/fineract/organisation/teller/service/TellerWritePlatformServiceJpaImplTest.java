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
package org.apache.fineract.organisation.teller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccount;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepositoryWrapper;
import org.apache.fineract.organisation.staff.domain.StaffRepository;
import org.apache.fineract.organisation.teller.data.CashierTransactionDataValidator;
import org.apache.fineract.organisation.teller.domain.Cashier;
import org.apache.fineract.organisation.teller.domain.CashierRepository;
import org.apache.fineract.organisation.teller.domain.CashierTransactionRepository;
import org.apache.fineract.organisation.teller.domain.Teller;
import org.apache.fineract.organisation.teller.domain.TellerRepositoryWrapper;
import org.apache.fineract.organisation.teller.serialization.TellerCommandFromApiJsonDeserializer;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TellerWritePlatformServiceJpaImplTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private TellerCommandFromApiJsonDeserializer fromApiJsonDeserializer;
    @Mock
    private TellerRepositoryWrapper tellerRepositoryWrapper;
    @Mock
    private OfficeRepositoryWrapper officeRepositoryWrapper;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private CashierRepository cashierRepository;
    @Mock
    private CashierTransactionRepository cashierTxnRepository;
    @Mock
    private JournalEntryRepository glJournalEntryRepository;
    @Mock
    private FinancialActivityAccountRepositoryWrapper financialActivityAccountRepositoryWrapper;
    @Mock
    private CashierTransactionDataValidator cashierTransactionDataValidator;

    @InjectMocks
    private TellerWritePlatformServiceJpaImpl underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 1))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @SuppressWarnings("unchecked")
    @Test
    void allocateCashToCashierShouldBatchSaveJournalEntries() {
        Long cashierId = 7L;
        JsonCommand command = org.mockito.Mockito.mock(JsonCommand.class);
        AppUser currentUser = org.mockito.Mockito.mock(AppUser.class);
        Cashier cashier = org.mockito.Mockito.mock(Cashier.class);
        Teller teller = org.mockito.Mockito.mock(Teller.class);
        Office office = org.mockito.Mockito.mock(Office.class);
        FinancialActivityAccount mainVaultAccount = org.mockito.Mockito.mock(FinancialActivityAccount.class);
        FinancialActivityAccount tellerCashAccount = org.mockito.Mockito.mock(FinancialActivityAccount.class);
        GLAccount mainVaultGlAccount = org.mockito.Mockito.mock(GLAccount.class);
        GLAccount tellerCashGlAccount = org.mockito.Mockito.mock(GLAccount.class);

        when(context.authenticatedUser()).thenReturn(currentUser);
        when(currentUser.getId()).thenReturn(1L);
        when(cashierRepository.findById(cashierId)).thenReturn(Optional.of(cashier));
        when(command.json()).thenReturn("{}");
        when(command.stringValueOfParameterNamed("entityType")).thenReturn(null);
        when(command.integerValueOfParameterNamed("txnType")).thenReturn(1);
        when(command.bigDecimalValueOfParameterNamed("txnAmount")).thenReturn(BigDecimal.TEN);
        when(command.localDateValueOfParameterNamed("txnDate")).thenReturn(LocalDate.of(2026, 1, 1));
        when(command.stringValueOfParameterNamed("txnNote")).thenReturn("note");
        when(command.longValueOfParameterNamed("entityId")).thenReturn(null);
        when(command.stringValueOfParameterNamed("currencyCode")).thenReturn("USD");
        when(cashier.getTeller()).thenReturn(teller);
        when(teller.getOffice()).thenReturn(office);
        when(office.getId()).thenReturn(2L);
        when(mainVaultAccount.getGlAccount()).thenReturn(mainVaultGlAccount);
        when(tellerCashAccount.getGlAccount()).thenReturn(tellerCashGlAccount);
        when(financialActivityAccountRepositoryWrapper
                .findByFinancialActivityTypeWithNotFoundDetection(FinancialActivity.CASH_AT_MAINVAULT.getValue()))
                        .thenReturn(mainVaultAccount);
        when(financialActivityAccountRepositoryWrapper
                .findByFinancialActivityTypeWithNotFoundDetection(FinancialActivity.CASH_AT_TELLER.getValue()))
                        .thenReturn(tellerCashAccount);

        underTest.allocateCashToCashier(cashierId, command);

        ArgumentCaptor<Iterable<JournalEntry>> journalEntriesCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(glJournalEntryRepository).saveAll(journalEntriesCaptor.capture());
        verify(glJournalEntryRepository, never()).saveAndFlush(any(JournalEntry.class));
        List<JournalEntry> journalEntries = StreamSupport.stream(journalEntriesCaptor.getValue().spliterator(), false).toList();
        assertThat(journalEntries).hasSize(2);
        assertThat(journalEntries.stream().filter(JournalEntry::isDebitEntry).count()).isEqualTo(1);
        assertThat(journalEntries.stream().filter(JournalEntry::isCreditEntry).count()).isEqualTo(1);
        assertThat(journalEntries).extracting(JournalEntry::getCurrencyCode).containsOnly("USD");
    }
}
