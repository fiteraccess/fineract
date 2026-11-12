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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.service.BalanceValidationService;
import org.apache.fineract.portfolio.savings.service.CacheableSavingsProductConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** AB-529: a transaction that lands before the last interest posting must not correct interest outside Synapse. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavingsAccountDomainServiceJpaLateArrivalInterestTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 29);
    private static final LocalDate LATE_DATE = LocalDate.of(2026, 10, 26);

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

    private SavingsAccount account;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "test", "Test", "UTC", null));
        account = mock(SavingsAccount.class, RETURNS_DEEP_STUBS);
        when(account.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        when(account.allowDeposit()).thenReturn(true);
        when(account.allowWithdrawal()).thenReturn(true);
        when(account.isBeforeLastPostingPeriod(LATE_DATE, false)).thenReturn(true);
        when(account.isSynapseCreditRestricted()).thenReturn(false);
        SavingsAccountTransaction posted = mock(SavingsAccountTransaction.class);
        when(account.deposit(any(), any(), anyBoolean(), anyLong(), anyString())).thenReturn(posted);
        when(account.withdraw(any(), anyBoolean(), anyBoolean(), anyLong(), anyString(), any(), any())).thenReturn(posted);
        when(configurationDomainService.retrieveRelaxingDaysConfigForPivotDate()).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @ParameterizedTest(name = "synapse={0} flag={1} -> local re-post={2}")
    @CsvSource({ "true,true,false", "true,false,true", "false,true,true" })
    void lateDepositRepostsInterestLocallyOnlyWhenSynapseDoesNotPostIt(final boolean synapseEnabled, final boolean flagEnabled,
            final boolean expectLocalRepost) {
        SavingsAccountDomainServiceJpa service = service(synapseEnabled, flagEnabled);

        service.handleDeposit(account, DateTimeFormatter.ISO_LOCAL_DATE, LATE_DATE, BigDecimal.valueOf(400), null, false, true, false);

        verifyInterest(expectLocalRepost);
    }

    @ParameterizedTest(name = "synapse={0} flag={1} -> local re-post={2}")
    @CsvSource({ "true,true,false", "true,false,true", "false,true,true" })
    void lateWithdrawalRepostsInterestLocallyOnlyWhenSynapseDoesNotPostIt(final boolean synapseEnabled, final boolean flagEnabled,
            final boolean expectLocalRepost) {
        SavingsAccountDomainServiceJpa service = service(synapseEnabled, flagEnabled);

        service.handleWithdrawal(account, DateTimeFormatter.ISO_LOCAL_DATE, LATE_DATE, BigDecimal.valueOf(100), null,
                new SavingsTransactionBooleanValues(false, true, false, false, false), false);

        verifyInterest(expectLocalRepost);
    }

    private SavingsAccountDomainServiceJpa service(final boolean synapseEnabled, final boolean flagEnabled) {
        FineractProperties properties = new FineractProperties();
        FineractProperties.FineractSynapseProperties synapse = new FineractProperties.FineractSynapseProperties();
        synapse.setEnabled(synapseEnabled);
        properties.setSynapse(synapse);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(flagEnabled);
        return new SavingsAccountDomainServiceJpa(savingsAccountRepository, savingsAccountTransactionRepository,
                journalEntryWritePlatformService, configurationDomainService, context, depositAccountOnHoldTransactionRepository,
                businessEventNotifierService, balanceValidationService, entityManager, cacheableSavingsProductConfigService,
                savingsDailyBalanceSyncRepository, noteRepository, properties);
    }

    private void verifyInterest(final boolean expectLocalRepost) {
        verify(account, expectLocalRepost ? times(1) : never()).postInterest(any(MathContext.class), eq(BUSINESS_DATE), anyBoolean(),
                anyBoolean(), any(), isNull(), eq(false), anyBoolean());
        verify(account, expectLocalRepost ? never() : times(1)).calculateInterestUsing(any(MathContext.class), eq(BUSINESS_DATE),
                anyBoolean(), anyBoolean(), any(), isNull(), eq(false), anyBoolean());
        verify(savingsAccountTransactionRepository).saveAndFlush(any(SavingsAccountTransaction.class));
    }
}
