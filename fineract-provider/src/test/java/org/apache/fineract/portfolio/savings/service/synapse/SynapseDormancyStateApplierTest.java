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
package org.apache.fineract.portfolio.savings.service.synapse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTransactionDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseDormancyStateApplier.ApplyResult;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import sun.misc.Unsafe;

@ExtendWith(MockitoExtension.class)
class SynapseDormancyStateApplierTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 5, 1);
    private static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 4, 30);
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("2000.00");
    private static final BigDecimal ESCHEAT_AMOUNT = new BigDecimal("1500.00");
    private static final String ACCOUNT_CURRENCY = "USD";

    private static MockedStatic<MoneyHelper> moneyHelper;

    @Mock
    private SavingsAccountTransactionRepository transactionRepository;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    @Mock
    private AppUserRepositoryWrapper appUserRepository;

    private final SavingsAccountTransactionSummaryWrapper summaryWrapper = new SavingsAccountTransactionSummaryWrapper();
    private SynapseDormancyStateApplier applier;

    @BeforeAll
    static void initMoney() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void closeMoney() {
        moneyHelper.close();
    }

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
        applier = new SynapseDormancyStateApplier(transactionRepository, savingsAccountRepositoryWrapper,
                journalEntryWritePlatformService, appUserRepository, summaryWrapper);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Nested
    class ApplyInactive {

        @Test
        void inactive_persistsTransitionToInactive() throws Exception {
            SavingsAccount account = buildAccount(1L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());

            ApplyResult result = applier.apply(account, "trace-inactive-1", SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE_DATE, null,
                    ACCOUNT_CURRENCY);

            assertThat(account.getSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.INACTIVE.getValue());
            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.INACTIVE);
            assertThat(result.escheatTransaction()).isNull();
            assertThat(result.alreadyApplied()).isFalse();
            verify(savingsAccountRepositoryWrapper, times(1)).saveAndFlush(account);
            verifyNoInteractions(journalEntryWritePlatformService, transactionRepository, appUserRepository);
        }

        @Test
        void inactive_alreadyAtTarget_isNoOp() throws Exception {
            SavingsAccount account = buildAccount(2L, STARTING_BALANCE, SavingsAccountSubStatusEnum.INACTIVE.getValue());

            ApplyResult result = applier.apply(account, "trace-inactive-2", SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE_DATE, null,
                    ACCOUNT_CURRENCY);

            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.INACTIVE);
            assertThat(result.escheatTransaction()).isNull();
            assertThat(result.alreadyApplied()).isTrue();
            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, transactionRepository,
                    appUserRepository);
        }
    }

    @Nested
    class ApplyDormant {

        @Test
        void dormant_persistsTransitionToDormant() throws Exception {
            SavingsAccount account = buildAccount(3L, STARTING_BALANCE, SavingsAccountSubStatusEnum.INACTIVE.getValue());

            ApplyResult result = applier.apply(account, "trace-dormant-1", SavingsAccountSubStatusEnum.DORMANT, EFFECTIVE_DATE, null,
                    ACCOUNT_CURRENCY);

            assertThat(account.getSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.DORMANT.getValue());
            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.DORMANT);
            assertThat(result.escheatTransaction()).isNull();
            assertThat(result.alreadyApplied()).isFalse();
            verify(savingsAccountRepositoryWrapper, times(1)).saveAndFlush(account);
            verifyNoInteractions(journalEntryWritePlatformService, transactionRepository, appUserRepository);
        }

        @Test
        void dormant_alreadyAtTarget_isNoOp() throws Exception {
            SavingsAccount account = buildAccount(4L, STARTING_BALANCE, SavingsAccountSubStatusEnum.DORMANT.getValue());

            ApplyResult result = applier.apply(account, "trace-dormant-2", SavingsAccountSubStatusEnum.DORMANT, EFFECTIVE_DATE, null,
                    ACCOUNT_CURRENCY);

            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.DORMANT);
            assertThat(result.escheatTransaction()).isNull();
            assertThat(result.alreadyApplied()).isTrue();
            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, transactionRepository,
                    appUserRepository);
        }
    }

    @Nested
    class ApplyEscheat {

        @Test
        void escheat_buildsTransactionFromCallbackPayload() throws Exception {
            SavingsAccount account = buildEscheatableAccount(10L);
            AppUser systemUser = mock(AppUser.class);
            when(transactionRepository.findByRefNo("trace-escheat-1")).thenReturn(Collections.emptyList());
            when(appUserRepository.fetchSystemUser()).thenReturn(systemUser);
            stubSaveAssignsEscheatId(999L);

            ApplyResult result = applier.apply(account, "trace-escheat-1", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE,
                    ESCHEAT_AMOUNT, ACCOUNT_CURRENCY);

            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT);
            assertThat(result.alreadyApplied()).isFalse();
            assertThat(result.escheatTransaction()).isNotNull();
            assertThat(result.escheatTransaction().getTypeOf()).isEqualTo(SavingsAccountTransactionType.ESCHEAT.getValue());
            assertThat(result.escheatTransaction().getRefNo()).isEqualTo("trace-escheat-1");
            assertThat(result.escheatTransaction().getDateOf()).isEqualTo(EFFECTIVE_DATE);
            assertThat(result.escheatTransaction().getAmount()).isEqualByComparingTo(ESCHEAT_AMOUNT);
            assertThat(result.escheatTransaction().getId()).isEqualTo(999L);
            assertThat(account.getStatus()).isEqualTo(SavingsAccountStatusType.CLOSED);
            assertThat(account.getSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT.getValue());
            assertThat(account.getClosedOnDate()).isEqualTo(EFFECTIVE_DATE);
            assertThat(account.getClosedBy()).isSameAs(systemUser);
            assertThat(account.getTransactions()).containsExactly(result.escheatTransaction());
            assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(ESCHEAT_AMOUNT);
            assertThat(result.escheatTransaction().getRunningBalance()).isEqualByComparingTo(ESCHEAT_AMOUNT);
            verify(savingsAccountRepositoryWrapper, times(1)).saveAndFlush(account);

            ArgumentCaptor<SavingsAccountingBridgeDTO> bridgeCaptor = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
            verify(journalEntryWritePlatformService, times(1)).createJournalEntriesForSavings(bridgeCaptor.capture());
            SavingsAccountingBridgeDTO bridge = bridgeCaptor.getValue();
            assertThat(bridge.getSavingsId()).isEqualTo(10L);
            assertThat(bridge.getSavingsProductId()).isEqualTo(7L);
            assertThat(bridge.getOfficeId()).isEqualTo(1L);
            assertThat(bridge.getCurrencyCode()).isEqualTo(ACCOUNT_CURRENCY);
            assertThat(bridge.isCashBasedAccountingEnabled()).isTrue();
            assertThat(bridge.isAccrualBasedAccountingEnabled()).isFalse();
            assertThat(bridge.isAccountTransfer()).isFalse();
            assertThat(bridge.getNewSavingsTransactions()).hasSize(1);
            SavingsAccountingBridgeTransactionDTO txn = bridge.getNewSavingsTransactions().get(0);
            assertThat(txn.getId()).isEqualTo(999L);
            assertThat(txn.getOfficeId()).isEqualTo(1L);
            assertThat(txn.getType().getId()).isEqualTo((long) SavingsAccountTransactionType.ESCHEAT.getValue());
            assertThat(txn.isReversed()).isFalse();
            assertThat(txn.getDate()).isEqualTo(EFFECTIVE_DATE);
            assertThat(txn.getCurrencyCode()).isEqualTo(ACCOUNT_CURRENCY);
            assertThat(txn.getAmount()).isEqualByComparingTo(ESCHEAT_AMOUNT);
        }

        @Test
        void escheat_currencyMismatch_usesAccountCurrencyOnBridge() throws Exception {
            SavingsAccount account = buildEscheatableAccount(11L);
            when(transactionRepository.findByRefNo("trace-escheat-mismatch")).thenReturn(Collections.emptyList());
            when(appUserRepository.fetchSystemUser()).thenReturn(mock(AppUser.class));

            applier.apply(account, "trace-escheat-mismatch", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, ESCHEAT_AMOUNT, "EUR");

            ArgumentCaptor<SavingsAccountingBridgeDTO> bridgeCaptor = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
            verify(journalEntryWritePlatformService).createJournalEntriesForSavings(bridgeCaptor.capture());
            assertThat(bridgeCaptor.getValue().getCurrencyCode()).isEqualTo(ACCOUNT_CURRENCY);
            assertThat(bridgeCaptor.getValue().getNewSavingsTransactions().get(0).getCurrencyCode()).isEqualTo(ACCOUNT_CURRENCY);
        }

        @Test
        void escheat_idempotent_onTraceId() throws Exception {
            SavingsAccount account = buildAccount(12L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());
            Integer originalSubStatus = account.getSubStatus();
            SavingsAccountTransaction existing = SavingsAccountTransaction.escheat(account, EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), ESCHEAT_AMOUNT), "trace-dup");
            when(transactionRepository.findByRefNo("trace-dup")).thenReturn(List.of(existing));

            ApplyResult result = applier.apply(account, "trace-dup", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, ESCHEAT_AMOUNT,
                    ACCOUNT_CURRENCY);

            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT);
            assertThat(result.alreadyApplied()).isTrue();
            assertThat(result.escheatTransaction()).isSameAs(existing);
            assertThat(account.getSubStatus()).isEqualTo(originalSubStatus);
            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, appUserRepository);
        }

        @Test
        void escheat_existingTraceIdShortCircuitsBeforeValidation() throws Exception {
            SavingsAccount account = buildEscheatableAccount(22L);
            SavingsAccountTransaction existing = SavingsAccountTransaction.escheat(account, EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), ESCHEAT_AMOUNT), "trace-precheck");
            when(transactionRepository.findByRefNo("trace-precheck")).thenReturn(List.of(existing));
            BigDecimal mismatchedAmount = ESCHEAT_AMOUNT.add(BigDecimal.ONE);

            ApplyResult result = applier.apply(account, "trace-precheck", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE,
                    mismatchedAmount, ACCOUNT_CURRENCY);

            assertThat(result.alreadyApplied()).isTrue();
            assertThat(result.escheatTransaction()).isSameAs(existing);
            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT);
            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, appUserRepository);
        }

        @Test
        void escheat_priorTraceIdOnDifferentAccount_doesNotShortCircuit() throws Exception {
            SavingsAccount account = buildEscheatableAccount(13L);
            SavingsAccount otherAccount = buildAccount(999L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());
            SavingsAccountTransaction otherAccountEscheat = SavingsAccountTransaction.escheat(otherAccount, EFFECTIVE_DATE,
                    Money.of(otherAccount.getCurrency(), ESCHEAT_AMOUNT), "trace-x");
            when(transactionRepository.findByRefNo("trace-x")).thenReturn(List.of(otherAccountEscheat));
            when(appUserRepository.fetchSystemUser()).thenReturn(mock(AppUser.class));

            ApplyResult result = applier.apply(account, "trace-x", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, ESCHEAT_AMOUNT,
                    ACCOUNT_CURRENCY);

            assertEscheatProceeded(account, result, otherAccountEscheat, 13L, "trace-x");
        }

        @Test
        void escheat_reversedPriorEscheat_doesNotShortCircuit() throws Exception {
            SavingsAccount account = buildEscheatableAccount(14L);
            SavingsAccountTransaction reversedEscheat = SavingsAccountTransaction.escheat(account, EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), ESCHEAT_AMOUNT), "trace-rev");
            setField(SavingsAccountTransaction.class, reversedEscheat, "reversed", true);
            when(transactionRepository.findByRefNo("trace-rev")).thenReturn(List.of(reversedEscheat));
            when(appUserRepository.fetchSystemUser()).thenReturn(mock(AppUser.class));

            ApplyResult result = applier.apply(account, "trace-rev", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, ESCHEAT_AMOUNT,
                    ACCOUNT_CURRENCY);

            assertEscheatProceeded(account, result, reversedEscheat, 14L, "trace-rev");
        }

        @Test
        void escheat_priorTraceIdOnNonEscheatTxn_doesNotShortCircuit() throws Exception {
            SavingsAccount account = buildEscheatableAccount(15L);
            SavingsAccountTransaction chargeTxn = SavingsAccountTransaction.charge(account, account.office(), EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), new BigDecimal("10.00")));
            setField(SavingsAccountTransaction.class, chargeTxn, "refNo", "trace-charge");
            when(transactionRepository.findByRefNo("trace-charge")).thenReturn(List.of(chargeTxn));
            when(appUserRepository.fetchSystemUser()).thenReturn(mock(AppUser.class));

            ApplyResult result = applier.apply(account, "trace-charge", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, ESCHEAT_AMOUNT,
                    ACCOUNT_CURRENCY);

            assertEscheatProceeded(account, result, chargeTxn, 15L, "trace-charge");
        }

        @Test
        void escheat_zeroAmount_persistsCloseStateWithoutTransaction() throws Exception {
            SavingsAccount account = buildAccount(16L, BigDecimal.ZERO, SavingsAccountSubStatusEnum.NONE.getValue());
            AppUser systemUser = mock(AppUser.class);
            when(transactionRepository.findByRefNo("trace-zero")).thenReturn(Collections.emptyList());
            when(appUserRepository.fetchSystemUser()).thenReturn(systemUser);

            ApplyResult result = applier.apply(account, "trace-zero", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, BigDecimal.ZERO,
                    ACCOUNT_CURRENCY);

            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT);
            assertThat(result.alreadyApplied()).isFalse();
            assertThat(result.escheatTransaction()).isNull();
            assertThat(account.getStatus()).isEqualTo(SavingsAccountStatusType.CLOSED);
            assertThat(account.getSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT.getValue());
            assertThat(account.getClosedOnDate()).isEqualTo(EFFECTIVE_DATE);
            assertThat(account.getClosedBy()).isSameAs(systemUser);
            assertThat(account.getTransactions()).isEmpty();
            assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(savingsAccountRepositoryWrapper).saveAndFlush(account);
            verifyNoInteractions(journalEntryWritePlatformService);
        }

        @Test
        void escheat_priorTraceIdLaterInResultList_isNoOp() throws Exception {
            SavingsAccount account = buildAccount(17L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());
            setField(SavingsAccount.class, account, "status", SavingsAccountStatusType.ACTIVE.getValue());
            SavingsAccount otherAccount = buildAccount(998L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());
            SavingsAccountStatusType originalStatus = account.getStatus();
            Integer originalSubStatus = account.getSubStatus();
            SavingsAccountTransaction otherAccountEscheat = SavingsAccountTransaction.escheat(otherAccount, EFFECTIVE_DATE,
                    Money.of(otherAccount.getCurrency(), ESCHEAT_AMOUNT), "trace-multi");
            SavingsAccountTransaction reversedEscheat = SavingsAccountTransaction.escheat(account, EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), ESCHEAT_AMOUNT), "trace-multi");
            setField(SavingsAccountTransaction.class, reversedEscheat, "reversed", true);
            SavingsAccountTransaction validEscheat = SavingsAccountTransaction.escheat(account, EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), ESCHEAT_AMOUNT), "trace-multi");
            when(transactionRepository.findByRefNo("trace-multi"))
                    .thenReturn(List.of(otherAccountEscheat, reversedEscheat, validEscheat));

            ApplyResult result = applier.apply(account, "trace-multi", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE, ESCHEAT_AMOUNT,
                    ACCOUNT_CURRENCY);

            assertThat(result.alreadyApplied()).isTrue();
            assertThat(result.escheatTransaction()).isSameAs(validEscheat);
            assertThat(result.appliedSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT);
            assertThat(account.getStatus()).isEqualTo(originalStatus);
            assertThat(account.getSubStatus()).isEqualTo(originalSubStatus);
            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, appUserRepository);
        }

        @Test
        void escheat_effectiveDateBeforeLatestTransactionDate_isRejected() throws Exception {
            SavingsAccount account = buildEscheatableAccount(18L);
            setField(SavingsAccount.class, account, "status", SavingsAccountStatusType.ACTIVE.getValue());
            SavingsAccountTransaction prior = SavingsAccountTransaction.charge(account, account.office(), EFFECTIVE_DATE.plusDays(1),
                    Money.of(account.getCurrency(), new BigDecimal("10.00")));
            account.getTransactions().add(prior);
            when(transactionRepository.findByRefNo("trace-backdated")).thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> applier.apply(account, "trace-backdated", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE,
                    ESCHEAT_AMOUNT, ACCOUNT_CURRENCY))
                            .isInstanceOfSatisfying(PlatformApiDataValidationException.class,
                                    ex -> assertThat(ex.getErrors()).extracting(ApiParameterError::getUserMessageGlobalisationCode)
                                            .containsExactly("error.msg.savings.escheat.backdated"));

            verifyNoInteractions(savingsAccountRepositoryWrapper);
            verifyNoInteractions(journalEntryWritePlatformService);
            verifyNoInteractions(appUserRepository);
            assertThat(account.getStatus()).isNotEqualTo(SavingsAccountStatusType.CLOSED);
        }

        @Test
        void escheat_effectiveDateEqualToLatestTransactionDate_proceeds() throws Exception {
            SavingsAccount account = buildEscheatableAccount(23L);
            SavingsAccountTransaction sameDayCharge = SavingsAccountTransaction.charge(account, account.office(), EFFECTIVE_DATE,
                    Money.of(account.getCurrency(), new BigDecimal("10.00")));
            account.getTransactions().add(sameDayCharge);
            when(transactionRepository.findByRefNo("trace-equal-date")).thenReturn(Collections.emptyList());
            when(appUserRepository.fetchSystemUser()).thenReturn(mock(AppUser.class));

            ApplyResult result = applier.apply(account, "trace-equal-date", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE,
                    ESCHEAT_AMOUNT, ACCOUNT_CURRENCY);

            assertEscheatProceeded(account, result, sameDayCharge, 23L, "trace-equal-date");
        }

        @Test
        void escheat_amountDiffersFromAccountBalance_isRejected() throws Exception {
            SavingsAccount account = buildEscheatableAccount(19L);
            BigDecimal mismatchedAmount = ESCHEAT_AMOUNT.add(BigDecimal.ONE);
            when(transactionRepository.findByRefNo("trace-mismatch")).thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> applier.apply(account, "trace-mismatch", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE,
                    mismatchedAmount, ACCOUNT_CURRENCY))
                            .isInstanceOfSatisfying(PlatformApiDataValidationException.class,
                                    ex -> assertThat(ex.getErrors()).extracting(ApiParameterError::getUserMessageGlobalisationCode)
                                            .containsExactly("error.msg.savings.escheat.amount.mismatch"));

            verifyNoInteractions(savingsAccountRepositoryWrapper);
            verifyNoInteractions(journalEntryWritePlatformService);
            verifyNoInteractions(appUserRepository);
        }

        @Test
        void escheat_amountLessThanAccountBalance_isRejected() throws Exception {
            SavingsAccount account = buildEscheatableAccount(24L);
            BigDecimal underPaid = ESCHEAT_AMOUNT.subtract(BigDecimal.ONE);
            when(transactionRepository.findByRefNo("trace-under")).thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> applier.apply(account, "trace-under", SavingsAccountSubStatusEnum.ESCHEAT, EFFECTIVE_DATE,
                    underPaid, ACCOUNT_CURRENCY))
                            .isInstanceOfSatisfying(PlatformApiDataValidationException.class,
                                    ex -> assertThat(ex.getErrors()).extracting(ApiParameterError::getUserMessageGlobalisationCode)
                                            .containsExactly("error.msg.savings.escheat.amount.mismatch"));

            verifyNoInteractions(savingsAccountRepositoryWrapper);
            verifyNoInteractions(journalEntryWritePlatformService);
            verifyNoInteractions(appUserRepository);
        }

        private void assertEscheatProceeded(SavingsAccount account, ApplyResult result, SavingsAccountTransaction priorTxn,
                Long expectedAccountId, String expectedTraceId) {
            assertThat(result.alreadyApplied()).isFalse();
            assertThat(result.escheatTransaction()).isNotSameAs(priorTxn);
            assertThat(result.escheatTransaction().getSavingsAccount().getId()).isEqualTo(expectedAccountId);
            assertThat(result.escheatTransaction().getTypeOf()).isEqualTo(SavingsAccountTransactionType.ESCHEAT.getValue());
            assertThat(result.escheatTransaction().getRefNo()).isEqualTo(expectedTraceId);
            assertThat(result.escheatTransaction().getDateOf()).isEqualTo(EFFECTIVE_DATE);
            assertThat(result.escheatTransaction().getAmount()).isEqualByComparingTo(ESCHEAT_AMOUNT);
            assertThat(account.getStatus()).isEqualTo(SavingsAccountStatusType.CLOSED);
            assertThat(account.getSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.ESCHEAT.getValue());
            verify(savingsAccountRepositoryWrapper).saveAndFlush(account);

            ArgumentCaptor<SavingsAccountingBridgeDTO> bridgeCaptor = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
            verify(journalEntryWritePlatformService).createJournalEntriesForSavings(bridgeCaptor.capture());
            SavingsAccountingBridgeDTO bridge = bridgeCaptor.getValue();
            assertThat(bridge.getSavingsId()).isEqualTo(expectedAccountId);
            assertThat(bridge.getNewSavingsTransactions()).hasSize(1);
            SavingsAccountingBridgeTransactionDTO txn = bridge.getNewSavingsTransactions().get(0);
            assertThat(txn.getType().getId()).isEqualTo((long) SavingsAccountTransactionType.ESCHEAT.getValue());
            assertThat(txn.getDate()).isEqualTo(EFFECTIVE_DATE);
            assertThat(txn.getAmount()).isEqualByComparingTo(ESCHEAT_AMOUNT);
            assertThat(txn.isReversed()).isFalse();
        }
    }

    @Nested
    class ApplyUnsupported {

        @Test
        void target_NONE_throwsIllegalArgument() throws Exception {
            SavingsAccount account = buildAccount(20L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());

            assertThatThrownBy(() -> applier.apply(account, "trace-none", SavingsAccountSubStatusEnum.NONE, EFFECTIVE_DATE, null,
                    ACCOUNT_CURRENCY)).isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Unsupported dormancy target sub-status: NONE");

            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, transactionRepository,
                    appUserRepository);
        }

        @Test
        void target_BLOCK_throwsIllegalArgument() throws Exception {
            SavingsAccount account = buildAccount(21L, STARTING_BALANCE, SavingsAccountSubStatusEnum.NONE.getValue());

            assertThatThrownBy(() -> applier.apply(account, "trace-block", SavingsAccountSubStatusEnum.BLOCK, EFFECTIVE_DATE, null,
                    ACCOUNT_CURRENCY)).isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Unsupported dormancy target sub-status: BLOCK");

            verifyNoInteractions(savingsAccountRepositoryWrapper, journalEntryWritePlatformService, transactionRepository,
                    appUserRepository);
        }
    }

    private SavingsAccount buildAccount(Long id, BigDecimal balance, Integer subStatus) throws Exception {
        MonetaryCurrency usd = new MonetaryCurrency(ACCOUNT_CURRENCY, 2, null);
        Office office = Office.headOffice("HQ", LocalDate.of(2020, 1, 1), null);
        setField(AbstractPersistableCustom.class, office, "id", 1L);
        Client client = new Client() {};
        client.setOffice(office);
        setField(AbstractPersistableCustom.class, client, "id", id);
        java.lang.reflect.Constructor<SavingsAccountSummary> ctor = SavingsAccountSummary.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        SavingsAccountSummary summary = ctor.newInstance();
        summary.setAccountBalance(balance);
        SavingsAccount account = new SavingsAccount() {};
        setField(AbstractPersistableCustom.class, account, "id", id);
        setField(SavingsAccount.class, account, "currency", usd);
        setField(SavingsAccount.class, account, "client", client);
        setField(SavingsAccount.class, account, "summary", summary);
        setField(SavingsAccount.class, account, "charges", new HashSet<SavingsAccountCharge>());
        setField(SavingsAccount.class, account, "transactions", new ArrayList<SavingsAccountTransaction>());
        setField(SavingsAccount.class, account, "sub_status", subStatus);
        setField(SavingsAccount.class, account, "savingsAccountTransactionSummaryWrapper", summaryWrapper);
        return account;
    }

    private SavingsAccount buildEscheatableAccount(Long id) throws Exception {
        SavingsAccount account = buildAccount(id, ESCHEAT_AMOUNT, SavingsAccountSubStatusEnum.NONE.getValue());
        setField(SavingsAccount.class, account, "product", buildSavingsProduct(7L));
        return account;
    }

    // SavingsProduct is an owned domain type with no public no-arg ctor; allocate uninitialised and set only the fields
    // SavingsAccountingBridgeDataHelper reads.
    @SuppressWarnings("restriction")
    private static SavingsProduct buildSavingsProduct(Long id) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        SavingsProduct product = (SavingsProduct) unsafe.allocateInstance(SavingsProduct.class);
        setField(AbstractPersistableCustom.class, product, "id", id);
        setField(SavingsProduct.class, product, "accountingRule", AccountingRuleType.CASH_BASED.getValue());
        return product;
    }

    private void stubSaveAssignsEscheatId(Long assignedId) {
        doAnswer(invocation -> {
            SavingsAccount saved = invocation.getArgument(0);
            for (SavingsAccountTransaction tx : saved.getTransactions()) {
                if (SavingsAccountTransactionType.ESCHEAT.getValue().equals(tx.getTypeOf())) {
                    Field f = AbstractPersistableCustom.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(tx, assignedId);
                }
            }
            return saved;
        }).when(savingsAccountRepositoryWrapper).saveAndFlush(any(SavingsAccount.class));
    }

    private static <T> T mock(Class<T> type) {
        return Mockito.mock(type);
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
