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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.junit.jupiter.api.Test;

class GoalSavingsAccrualTest {

    @org.junit.jupiter.api.BeforeEach
    void setBusinessDate() {
        org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.setBusinessDates(new java.util.HashMap<>(java.util.Map
                .of(org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 2))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearBusinessDate() {
        org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.reset();
    }

    @Test
    void followsPrincipalAndRoundsEachDayHalfUp() {
        assertThat(GoalSavingsAccrual.dailyAmount(new BigDecimal("200000"), new BigDecimal("10"), 2)).isEqualByComparingTo("54.79");
        assertThat(GoalSavingsAccrual.dailyAmount(new BigDecimal("150000"), new BigDecimal("10"), 2)).isEqualByComparingTo("41.10");
        assertThat(GoalSavingsAccrual.dailyAmount(new BigDecimal("250000"), new BigDecimal("10"), 2)).isEqualByComparingTo("68.49");
        assertThat(GoalSavingsAccrual.dailyAmount(new BigDecimal("456.25"), new BigDecimal("10"), 2)).isEqualByComparingTo("0.13");
    }

    @Test
    void interestPayableNeverCompoundsAndSameDayDepositsChangeEndOfDayBase() {
        try (var money = mockStatic(MoneyHelper.class)) {
            money.when(MoneyHelper::getMathContext).thenReturn(new MathContext(15, RoundingMode.HALF_UP));
            money.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_UP);
            var account = mock(SavingsAccount.class);
            var currency = new MonetaryCurrency("NGN", 2, null);
            var office = mock(Office.class);
            var day = LocalDate.of(2026, 1, 1);
            var txs = new ArrayList<SavingsAccountTransaction>();
            txs.add(SavingsAccountTransaction.deposit(account, office, null, day, Money.of(currency, new BigDecimal("200000")), null));
            txs.add(SavingsAccountTransaction.accrual(account, office, day, Money.of(currency, new BigDecimal("3000")), false, null));
            when(account.getTransactions()).thenReturn(txs);
            when(account.getCurrency()).thenReturn(currency);
            when(account.office()).thenReturn(office);
            when(account.getActivationDate()).thenReturn(day);
            when(account.getAccruedTillDate()).thenReturn(day);
            when(account.getNominalAnnualInterestRate()).thenReturn(new BigDecimal("10"));
            var captured = org.mockito.ArgumentCaptor.forClass(SavingsAccountTransaction.class);
            GoalSavingsAccrual.accrue(account, day.plusDays(1));
            verify(account).addTransaction(captured.capture());
            assertThat(captured.getValue().getAmount()).isEqualByComparingTo("54.79");
            assertThat(captured.getValue().getRunningBalance()).isEqualByComparingTo("200000");
        }
    }

    @Test
    void forfeitureSuppressesFutureAccrualWithoutLoadingHistory() {
        var account = mock(SavingsAccount.class);
        when(account.isGoalAccrualStopped()).thenReturn(true);
        GoalSavingsAccrual.accrue(account, LocalDate.of(2026, 10, 13));
        verify(account, never()).getTransactions();
    }
}
