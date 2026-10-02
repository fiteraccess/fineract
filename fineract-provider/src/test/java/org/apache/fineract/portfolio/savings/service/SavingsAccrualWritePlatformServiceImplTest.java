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
package org.apache.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.data.SavingsAccrualData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

class SavingsAccrualWritePlatformServiceImplTest {

    private static final LocalDate TILL = LocalDate.of(2026, 10, 9);

    private final SavingsAccountReadPlatformService readService = mock(SavingsAccountReadPlatformService.class);
    private final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
    private final SavingsAccountRepositoryWrapper accountRepository = mock(SavingsAccountRepositoryWrapper.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SavingsAccrualWritePlatformServiceImpl service = new SavingsAccrualWritePlatformServiceImpl(readService, assembler,
            accountRepository, mock(ConfigurationDomainService.class), mock(SavingsAccountDomainService.class), transactionManager);

    private MockedStatic<MoneyHelper> moneyHelper;

    @BeforeEach
    void mockMoney() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
    }

    @AfterEach
    void closeMoney() {
        moneyHelper.close();
    }

    @Test
    void anAccountChangedMidRunIsRetriedInAFreshTransaction() {
        SavingsAccount account = mock(SavingsAccount.class);
        when(readService.retrievePeriodicAccrualData(TILL, null)).thenReturn(List.of(accrual(309L)));
        when(assembler.assembleFrom(309L, false)).thenThrow(new ObjectOptimisticLockingFailureException(SavingsAccount.class, 309L))
                .thenReturn(account);

        assertThatCode(() -> service.addAccrualEntries(TILL)).doesNotThrowAnyException();

        verify(accountRepository).saveAndFlush(account);
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager).commit(any());
    }

    @Test
    void oneFailingAccountNoLongerRollsBackTheOthers() {
        SavingsAccount healthy = mock(SavingsAccount.class);
        when(readService.retrievePeriodicAccrualData(TILL, null)).thenReturn(List.of(accrual(1L), accrual(2L)));
        when(assembler.assembleFrom(1L, false)).thenThrow(new IllegalStateException("boom"));
        when(assembler.assembleFrom(2L, false)).thenReturn(healthy);

        assertThatThrownBy(() -> service.addAccrualEntries(TILL)).isInstanceOf(JobExecutionException.class);

        verify(accountRepository).saveAndFlush(healthy);
        verify(transactionManager).commit(any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void aConflictThatOutlastsTheRetriesFailsOnlyThatAccount() {
        when(readService.retrievePeriodicAccrualData(TILL, null)).thenReturn(List.of(accrual(5L)));
        when(assembler.assembleFrom(eq(5L), eq(false))).thenThrow(new ObjectOptimisticLockingFailureException(SavingsAccount.class, 5L));

        assertThatThrownBy(() -> service.addAccrualEntries(TILL)).isInstanceOf(JobExecutionException.class);

        verify(assembler, times(3)).assembleFrom(5L, false);
        verify(transactionManager, never()).commit(any());
    }

    private static SavingsAccrualData accrual(Long id) {
        // Accrued past the till date, so no day is booked and only the transaction boundaries are exercised.
        return new SavingsAccrualData(id, "94000" + id, TILL.plusDays(1), false, false, 100);
    }
}
