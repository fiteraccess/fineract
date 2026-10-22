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
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.data.CacheableSavingsProductConfig;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.service.BalanceValidationService;
import org.apache.fineract.portfolio.savings.service.CacheableSavingsProductConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SavingsAccountDomainServiceJpaOverdraftTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 26);

    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;
    @Mock
    private BalanceValidationService balanceValidationService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private CacheableSavingsProductConfigService cacheableSavingsProductConfigService;
    @Mock
    private SavingsDailyBalanceSyncRepository savingsDailyBalanceSyncRepository;
    @Mock
    private NoteRepository noteRepository;

    private SavingsAccountDomainServiceJpa service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "test", "Test", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("test", 6);
        service = new SavingsAccountDomainServiceJpa(savingsAccountRepository, savingsAccountTransactionRepository,
                journalEntryWritePlatformService, configurationDomainService, context, depositAccountOnHoldTransactionRepository,
                businessEventNotifierService, balanceValidationService, entityManager, cacheableSavingsProductConfigService,
                savingsDailyBalanceSyncRepository, noteRepository, new FineractProperties());
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCache();
    }

    @Test
    void reversedAccrualsPostTheirContraEntriesAndLinkThem() {
        SavingsAccount account = accrualAccount();
        Office office = account.office();
        SavingsAccountTransaction accrual = SavingsAccountTransaction.accrual(account, office, BUSINESS_DATE,
                Money.of(account.getCurrency(), new BigDecimal("2.20")), false, null);
        accrual.setId(77L);
        when(savingsAccountTransactionRepository.findLiveAccrualsFrom(11L, BUSINESS_DATE)).thenReturn(List.of(accrual));

        service.reverseAccrualsFrom(account, BUSINESS_DATE);

        assertThat(accrual.isReversed()).isTrue();
        verify(savingsAccountTransactionRepository).saveAll(List.of(accrual));
        ArgumentCaptor<SavingsAccountingBridgeDTO> bridge = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
        verify(journalEntryWritePlatformService).createJournalEntriesForSavings(bridge.capture(), any(Office.class));
        assertThat(bridge.getValue().getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.isReversed()).isTrue();
            assertThat(transaction.getAmount()).isEqualByComparingTo("2.20");
        });
        verify(journalEntryWritePlatformService).linkSavingsReversalJournalEntries(List.of(77L));
    }

    @Test
    void noLiveAccrualsMeansNoJournalWork() {
        SavingsAccount account = mock(SavingsAccount.class);
        when(account.getId()).thenReturn(11L);
        when(savingsAccountTransactionRepository.findLiveAccrualsFrom(11L, BUSINESS_DATE)).thenReturn(List.of());

        service.reverseAccrualsFrom(account, BUSINESS_DATE);

        verifyNoInteractions(journalEntryWritePlatformService);
    }

    private static SavingsAccount accrualAccount() {
        SavingsAccount account = mock(SavingsAccount.class);
        SavingsProduct savingsProduct = mock(SavingsProduct.class);
        when(account.getId()).thenReturn(11L);
        when(account.productId()).thenReturn(22L);
        when(account.officeId()).thenReturn(33L);
        when(account.office()).thenReturn(mock(Office.class));
        when(account.getCurrency()).thenReturn(new MonetaryCurrency("NGN", 2, null));
        when(account.savingsProduct()).thenReturn(savingsProduct);
        when(savingsProduct.isAccrualBasedAccountingEnabled()).thenReturn(true);
        return account;
    }

    @Test
    void optimizedNipWithdrawalShouldCarryIncrementalPrincipalAndReferenceOverdraftIntoAccountingBridge() {
        SavingsAccount account = mock(SavingsAccount.class);
        SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
        SavingsProduct savingsProduct = mock(SavingsProduct.class);
        MonetaryCurrency currency = new MonetaryCurrency("NGN", 2, null);
        Office office = mock(Office.class);
        when(account.isTransactionsAllowed()).thenReturn(true);
        when(account.getActivationDate()).thenReturn(BUSINESS_DATE.minusYears(1));
        when(account.isAccountLocked(BUSINESS_DATE)).thenReturn(false);
        when(account.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        when(account.getWithdrawableBalanceWithoutMinimumBalance()).thenReturn(BigDecimal.valueOf(5));
        when(account.getSummary()).thenReturn(summary);
        when(summary.getAccountBalance()).thenReturn(BigDecimal.valueOf(15));
        when(summary.getAccountBalance(currency)).thenReturn(Money.of(currency, BigDecimal.valueOf(15)));
        when(account.getOnHoldFunds()).thenReturn(BigDecimal.valueOf(6));
        when(account.getSavingsHoldAmount()).thenReturn(BigDecimal.valueOf(4));
        when(account.getSubStatus()).thenReturn(0);
        when(account.getVersion()).thenReturn(1);
        when(account.getId()).thenReturn(11L);
        when(account.productId()).thenReturn(22L);
        when(account.officeId()).thenReturn(33L);
        when(account.office()).thenReturn(office);
        when(account.getCurrency()).thenReturn(currency);
        when(account.savingsProduct()).thenReturn(savingsProduct);
        when(savingsProduct.isCashBasedAccountingEnabled()).thenReturn(true);
        when(savingsProduct.isAccrualBasedAccountingEnabled()).thenReturn(false);
        when(entityManager.getFlushMode()).thenReturn(FlushModeType.AUTO);
        when(configurationDomainService.retrieveRelaxingDaysConfigForPivotDate()).thenReturn(0L);
        when(cacheableSavingsProductConfigService.getSavingsProduct(22L))
                .thenReturn(new CacheableSavingsProductConfig(22L, "Savings", "SAV", 2, BigDecimal.valueOf(20), true, false));
        when(savingsAccountTransactionRepository.saveAndFlush(any(SavingsAccountTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SavingsTransactionBooleanValues transactionFlags = new SavingsTransactionBooleanValues(false, false, false, false, false);
        ReferenceTransaction commission = new ReferenceTransaction(SavingsAccountTransactionType.COMMISSION, BigDecimal.valueOf(4),
                "Commission", new ReferenceTransaction.CommissionBreakdown(new ReferenceTransaction.CommissionBreakdownLeg(BigDecimal.ONE),
                        new ReferenceTransaction.CommissionBreakdownLeg(BigDecimal.valueOf(3))));
        ReferenceTransaction vat = new ReferenceTransaction(SavingsAccountTransactionType.VAT, BigDecimal.valueOf(2), "VAT", null);

        service.handleNipWithdrawal(account, DateTimeFormatter.ISO_LOCAL_DATE, BUSINESS_DATE, BigDecimal.TEN, null, transactionFlags,
                "NIBSS", List.of(commission, vat), false);

        ArgumentCaptor<SavingsAccountingBridgeDTO> bridgeCaptor = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
        verify(journalEntryWritePlatformService, times(3)).createJournalEntriesForSavings(bridgeCaptor.capture(), any(Office.class));
        assertThat(bridgeCaptor.getAllValues().get(0).getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.getAmount()).isEqualByComparingTo("10");
            assertThat(transaction.getOverdraftAmount()).isEqualByComparingTo("5");
            assertThat(transaction.getSwitchId()).isEqualTo("NIBSS");
        });
        assertThat(bridgeCaptor.getAllValues().get(1).getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.getAmount()).isEqualByComparingTo("4");
            assertThat(transaction.getOverdraftAmount()).isEqualByComparingTo("4");
            assertThat(transaction.getCommissionAllocation().switchFeeAmount()).isEqualByComparingTo("1");
            assertThat(transaction.getCommissionAllocation().bankCommissionAmount()).isEqualByComparingTo("3");
        });
        assertThat(bridgeCaptor.getAllValues().get(2).getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.getAmount()).isEqualByComparingTo("2");
            assertThat(transaction.getOverdraftAmount()).isEqualByComparingTo("2");
            assertThat(transaction.getTaxDetails()).isEmpty();
        });
        InOrder mutationOrder = inOrder(account, savingsAccountRepository);
        mutationOrder.verify(account).getWithdrawableBalanceWithoutMinimumBalance();
        mutationOrder.verify(savingsAccountRepository).applyWithdrawalDelta(11L, BigDecimal.TEN, BigDecimal.ZERO, 0, 1);
        mutationOrder.verify(account).syncAfterDeltaUpdate(BigDecimal.valueOf(5));
    }

    @Test
    void optimizedNipDepositShouldPersistSwitchAndClearedOverdraftOnPrincipalAndLinkedEmtLevy() {
        SavingsAccount account = mock(SavingsAccount.class);
        SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
        SavingsProduct savingsProduct = mock(SavingsProduct.class);
        MonetaryCurrency currency = new MonetaryCurrency("NGN", 2, null);
        Office office = mock(Office.class);
        when(account.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        when(account.allowDeposit()).thenReturn(true);
        when(account.getActivationDate()).thenReturn(BUSINESS_DATE.minusYears(1));
        when(account.getSummary()).thenReturn(summary);
        when(summary.getAccountBalance()).thenReturn(BigDecimal.valueOf(-30));
        when(summary.getAccountBalance(currency)).thenReturn(Money.of(currency, BigDecimal.valueOf(-30)));
        when(account.getOnHoldFunds()).thenReturn(BigDecimal.ZERO);
        when(account.getSavingsHoldAmount()).thenReturn(BigDecimal.ZERO);
        when(account.getSubStatus()).thenReturn(0);
        when(account.getVersion()).thenReturn(1);
        when(account.getId()).thenReturn(11L);
        when(account.productId()).thenReturn(22L);
        when(account.office()).thenReturn(office);
        when(account.getCurrency()).thenReturn(currency);
        when(account.savingsProduct()).thenReturn(savingsProduct);
        when(savingsProduct.isCashBasedAccountingEnabled()).thenReturn(true);
        when(savingsProduct.isAccrualBasedAccountingEnabled()).thenReturn(false);
        when(entityManager.getFlushMode()).thenReturn(FlushModeType.AUTO);
        when(configurationDomainService.retrieveRelaxingDaysConfigForPivotDate()).thenReturn(0L);
        when(cacheableSavingsProductConfigService.getSavingsProduct(22L))
                .thenReturn(new CacheableSavingsProductConfig(22L, "Savings", "SAV", 2, BigDecimal.ZERO, true, false));
        when(savingsAccountTransactionRepository.saveAndFlush(any(SavingsAccountTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.handleNipDeposit(account, DateTimeFormatter.ISO_LOCAL_DATE, BUSINESS_DATE, BigDecimal.valueOf(100), null, "NIBSS",
                List.of(new ReferenceTransaction(SavingsAccountTransactionType.EMT_LEVY, BigDecimal.valueOf(50), null, null)), false, true,
                false);

        ArgumentCaptor<SavingsAccountTransaction> transactionCaptor = ArgumentCaptor.forClass(SavingsAccountTransaction.class);
        verify(savingsAccountTransactionRepository, times(2)).saveAndFlush(transactionCaptor.capture());
        assertThat(transactionCaptor.getAllValues()).allSatisfy(transaction -> assertThat(transaction.getSwitchId()).isEqualTo("NIBSS"));
        assertThat(transactionCaptor.getAllValues().getFirst().getOverdraftAmount()).isEqualByComparingTo("30");
        assertThat(transactionCaptor.getAllValues()).extracting(SavingsAccountTransaction::getRefNo).doesNotContainNull()
                .containsOnly(transactionCaptor.getAllValues().getFirst().getRefNo());
    }

    @Nested
    class ReferencePosting {

        @ParameterizedTest
        @ValueSource(booleans = { false, true })
        void sameDayReferencesDoNotRequireEitherTimeline(final boolean pivotEnabled) {
            SavingsAccount account = referenceAccount();
            MonetaryCurrency currency = account.getCurrency();
            Office office = account.office();
            SavingsAccountTransaction parent = SavingsAccountTransaction.withdrawal(account, office, null, BUSINESS_DATE,
                    Money.of(currency, BigDecimal.TEN), "same-day-bundle");
            parent.setRunningBalance(Money.of(currency, BigDecimal.valueOf(5)));
            ArgumentCaptor<SavingsAccountTransaction> historyAccess = ArgumentCaptor.forClass(SavingsAccountTransaction.class);
            // Either attachment would require a timeline that the optimized caller has not loaded.
            lenient().doThrow(new AssertionError("Same-day reference accessed full transaction history")).when(account)
                    .addTransaction(historyAccess.capture());
            lenient().doThrow(new AssertionError("Same-day reference accessed pivot transaction history")).when(account)
                    .addTransactionToExisting(historyAccess.capture());
            ReferenceTransaction reference = new ReferenceTransaction(SavingsAccountTransactionType.EMT_LEVY, BigDecimal.valueOf(7), "EMT",
                    null);

            List<SavingsAccountTransaction> created = service.applyReferenceTransactions(account, parent, List.of(reference), false,
                    pivotEnabled);

            assertThat(created).singleElement().satisfies(transaction -> {
                assertThat(transaction.getTypeOf()).isEqualTo(SavingsAccountTransactionType.EMT_LEVY.getValue());
                assertThat(transaction.getAmount()).isEqualByComparingTo("7");
                assertThat(transaction.getRunningBalance(currency).getAmount()).isEqualByComparingTo("-2");
                assertThat(transaction.getOverdraftAmount(currency).getAmount()).isEqualByComparingTo("2");
                assertThat(transaction.getRefNo()).isEqualTo("same-day-bundle");
                assertThat(transaction.getTransactionDate()).isEqualTo(BUSINESS_DATE);
            });
            verify(savingsAccountTransactionRepository).saveAndFlush(created.getFirst());
            verify(savingsAccountRepository).applyReferenceTransactionDelta(11L, BigDecimal.valueOf(7), 1);
            verify(account).syncAfterDeltaUpdate(new BigDecimal("8.00"));
            ArgumentCaptor<SavingsAccountingBridgeDTO> bridge = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
            verify(journalEntryWritePlatformService).createJournalEntriesForSavings(bridge.capture(), eq(office));
            assertThat(bridge.getValue().getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
                assertThat(transaction.getAmount()).isEqualByComparingTo("7");
                assertThat(transaction.getOverdraftAmount()).isEqualByComparingTo("2");
            });
        }

        @ParameterizedTest
        @CsvSource({ "false, 1, 0", "true, 0, 1" })
        void backdatedReferencesRetainTheirTimeline(final boolean pivotEnabled, final int fullHistoryAttachments,
                final int pivotAttachments) {
            SavingsAccount account = referenceAccount();
            MonetaryCurrency currency = account.getCurrency();
            Office office = account.office();
            LocalDate date = BUSINESS_DATE.minusDays(1);
            SavingsAccountTransaction parent = SavingsAccountTransaction.withdrawal(account, office, null, date,
                    Money.of(currency, BigDecimal.TEN), "backdated-bundle");
            parent.setRunningBalance(Money.of(currency, BigDecimal.valueOf(5)));
            ReferenceTransaction commission = new ReferenceTransaction(SavingsAccountTransactionType.COMMISSION, BigDecimal.valueOf(4),
                    "Commission",
                    new ReferenceTransaction.CommissionBreakdown(new ReferenceTransaction.CommissionBreakdownLeg(BigDecimal.ONE),
                            new ReferenceTransaction.CommissionBreakdownLeg(BigDecimal.valueOf(3))));
            ReferenceTransaction vat = new ReferenceTransaction(SavingsAccountTransactionType.VAT, BigDecimal.valueOf(2), "VAT", null);

            List<SavingsAccountTransaction> created = service.applyReferenceTransactions(account, parent, List.of(commission, vat), true,
                    pivotEnabled);

            assertThat(created).hasSize(2);
            assertThat(created.get(0).getTypeOf()).isEqualTo(SavingsAccountTransactionType.COMMISSION.getValue());
            assertThat(created.get(0).getAmount()).isEqualByComparingTo("4");
            assertThat(created.get(0).getRunningBalance(currency).getAmount()).isEqualByComparingTo("1");
            assertThat(created.get(0).getOverdraftAmount(currency).getAmount()).isEqualByComparingTo("0");
            assertThat(created.get(1).getTypeOf()).isEqualTo(SavingsAccountTransactionType.VAT.getValue());
            assertThat(created.get(1).getAmount()).isEqualByComparingTo("2");
            assertThat(created.get(1).getRunningBalance(currency).getAmount()).isEqualByComparingTo("-1");
            assertThat(created.get(1).getOverdraftAmount(currency).getAmount()).isEqualByComparingTo("1");
            assertThat(created).extracting(SavingsAccountTransaction::getRefNo).containsExactly("backdated-bundle", "backdated-bundle");
            assertThat(created).extracting(SavingsAccountTransaction::getTransactionDate).containsExactly(date, date);
            verify(savingsAccountRepository).applyReferenceTransactionDelta(11L, BigDecimal.valueOf(4), 1);
            verify(savingsAccountRepository).applyReferenceTransactionDelta(11L, BigDecimal.valueOf(2), 1);
            verify(account).syncAfterDeltaUpdate(new BigDecimal("11.00"));
            verify(account).syncAfterDeltaUpdate(new BigDecimal("9.00"));
            verify(savingsAccountTransactionRepository).saveAndFlush(created.get(0));
            verify(savingsAccountTransactionRepository).saveAndFlush(created.get(1));
            verify(account, times(fullHistoryAttachments)).addTransaction(created.get(0));
            verify(account, times(fullHistoryAttachments)).addTransaction(created.get(1));
            verify(account, times(pivotAttachments)).addTransactionToExisting(created.get(0));
            verify(account, times(pivotAttachments)).addTransactionToExisting(created.get(1));
            ArgumentCaptor<SavingsAccountingBridgeDTO> bridge = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
            verify(journalEntryWritePlatformService, times(2)).createJournalEntriesForSavings(bridge.capture(), eq(office));
            assertThat(bridge.getAllValues()).allSatisfy(payload -> {
                assertThat(payload.getSavingsId()).isEqualTo(11L);
                assertThat(payload.getSavingsProductId()).isEqualTo(22L);
                assertThat(payload.getOfficeId()).isEqualTo(33L);
                assertThat(payload.getCurrencyCode()).isEqualTo("NGN");
                assertThat(payload.isAccountTransfer()).isTrue();
            });
            assertThat(bridge.getAllValues().get(0).getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
                assertThat(transaction.getAmount()).isEqualByComparingTo("4");
                assertThat(transaction.getOverdraftAmount()).isNull();
                assertThat(transaction.getCommissionAllocation().switchFeeAmount()).isEqualByComparingTo("1");
                assertThat(transaction.getCommissionAllocation().bankCommissionAmount()).isEqualByComparingTo("3");
            });
            assertThat(bridge.getAllValues().get(1).getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
                assertThat(transaction.getAmount()).isEqualByComparingTo("2");
                assertThat(transaction.getOverdraftAmount()).isEqualByComparingTo("1");
                assertThat(transaction.getTaxDetails()).isEmpty();
            });
        }

        @Test
        void emptyReferencesPerformNoWork() {
            SavingsAccount account = mock(SavingsAccount.class);
            SavingsAccountTransaction parent = mock(SavingsAccountTransaction.class);

            assertThat(service.applyReferenceTransactions(account, parent, List.of(), false, false)).isEmpty();

            verifyNoInteractions(account, parent, savingsAccountRepository, savingsAccountTransactionRepository, entityManager,
                    journalEntryWritePlatformService);
        }

        private SavingsAccount referenceAccount() {
            SavingsAccount account = mock(SavingsAccount.class);
            SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
            SavingsProduct product = mock(SavingsProduct.class);
            MonetaryCurrency currency = new MonetaryCurrency("NGN", 2, null);
            when(account.getId()).thenReturn(11L);
            when(account.productId()).thenReturn(22L);
            when(account.officeId()).thenReturn(33L);
            when(account.office()).thenReturn(mock(Office.class));
            when(account.getVersion()).thenReturn(1);
            when(account.getCurrency()).thenReturn(currency);
            when(account.getSummary()).thenReturn(summary);
            when(summary.getAccountBalance(currency)).thenReturn(Money.of(currency, BigDecimal.valueOf(15)));
            when(account.savingsProduct()).thenReturn(product);
            when(entityManager.getFlushMode()).thenReturn(FlushModeType.AUTO);
            return account;
        }
    }

    @Nested
    class NipWithdrawalNotes {

        @Test
        void savesOnlyNonblankEmtDescriptionVerbatim() {
            SavingsAccount account = mock(SavingsAccount.class);
            List<ReferenceTransaction> references = List.of(
                    new ReferenceTransaction(SavingsAccountTransactionType.EMT_LEVY, BigDecimal.valueOf(50), null, null),
                    new ReferenceTransaction(SavingsAccountTransactionType.EMT_LEVY, BigDecimal.valueOf(40), "  ", null),
                    new ReferenceTransaction(SavingsAccountTransactionType.EMT_LEVY, BigDecimal.valueOf(30), "  EMT note  ", null));
            List<SavingsAccountTransaction> createdReferences = List.of(mock(SavingsAccountTransaction.class),
                    mock(SavingsAccountTransaction.class), mock(SavingsAccountTransaction.class));

            service.saveNipWithdrawalReferenceNotes(account, references, createdReferences);

            ArgumentCaptor<Note> noteCaptor = ArgumentCaptor.forClass(Note.class);
            verify(noteRepository).save(noteCaptor.capture());
            assertThat(noteCaptor.getValue().getNote()).isEqualTo("  EMT note  ");
        }

        @Test
        void preservesCommissionAndVatNotes() {
            SavingsAccount account = mock(SavingsAccount.class);
            List<ReferenceTransaction> references = List.of(
                    new ReferenceTransaction(SavingsAccountTransactionType.COMMISSION, BigDecimal.valueOf(22), "Commission note", null),
                    new ReferenceTransaction(SavingsAccountTransactionType.VAT, BigDecimal.valueOf(2), "VAT note", null));
            List<SavingsAccountTransaction> createdReferences = List.of(mock(SavingsAccountTransaction.class),
                    mock(SavingsAccountTransaction.class));

            service.saveNipWithdrawalReferenceNotes(account, references, createdReferences);

            ArgumentCaptor<Note> noteCaptor = ArgumentCaptor.forClass(Note.class);
            verify(noteRepository, times(2)).save(noteCaptor.capture());
            assertThat(noteCaptor.getAllValues()).extracting(Note::getNote).containsExactly("Commission note", "VAT note");
        }
    }

    @ParameterizedTest
    @CsvSource({ "20, 10, 0", "10, 10, 0", "6, 10, 4", "0, 10, 10", "-7, 10, 10" })
    void incrementalOverdraftShouldCoverFundedCrossingAndAlreadyOverdrawnDebits(final BigDecimal availableBalanceBeforeDebit,
            final BigDecimal debitAmount, final BigDecimal expectedIncrementalOverdraft) {
        assertThat(SavingsAccountDomainServiceJpa.calculateIncrementalOverdraftAmount(availableBalanceBeforeDebit, debitAmount))
                .isEqualByComparingTo(expectedIncrementalOverdraft);
    }

    @Test
    void withdrawableBalanceWithoutMinimumBalanceShouldSubtractBothHoldCategories() throws Exception {
        SavingsAccount account = new SavingsAccount();
        SavingsAccountSummary summary = new SavingsAccountSummary();
        summary.setAccountBalance(BigDecimal.valueOf(15));
        Field summaryField = SavingsAccount.class.getDeclaredField("summary");
        summaryField.setAccessible(true);
        summaryField.set(account, summary);
        Field currencyField = SavingsAccount.class.getDeclaredField("currency");
        currencyField.setAccessible(true);
        currencyField.set(account, new MonetaryCurrency("NGN", 2, null));
        account.holdFunds(BigDecimal.valueOf(6));
        account.holdAmount(BigDecimal.valueOf(4));

        assertThat(account.getWithdrawableBalanceWithoutMinimumBalance()).isEqualByComparingTo("5");
    }
}
