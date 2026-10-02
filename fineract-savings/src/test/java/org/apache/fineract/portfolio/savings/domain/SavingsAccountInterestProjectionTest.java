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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AB-401: the accrual job books from {@link SavingsAccount#projectInterestUsing}, so it must earn what posting's
 * {@link SavingsAccount#calculateInterestUsing} earns even when the stored balance-end dates were never derived.
 */
class SavingsAccountInterestProjectionTest {

    private static final LocalDate ACTIVATION = LocalDate.of(2026, 8, 1);
    private static final LocalDate UP_TO = LocalDate.of(2026, 8, 20);
    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, null);
    private static final MathContext MC = new MathContext(15, RoundingMode.HALF_EVEN);

    private final Office office = mock(Office.class);

    @BeforeEach
    void setBusinessDate() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, UP_TO)));
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());
    }

    @AfterEach
    void resetThreadLocalContext() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCache();
    }

    @Test
    void projectionEarnsWhatPostingEarnsWhenStoredBalanceDatesAreMissing() {
        SavingsAccount projected = accountWith(LocalDate.of(2026, 8, 11));
        SavingsAccount posted = accountWith(LocalDate.of(2026, 8, 11));

        Money projectedInterest = interestOf(projected.projectInterestUsing(MC, UP_TO, false, 1));
        Money postedInterest = interestOf(posted.calculateInterestUsing(MC, UP_TO, false, false, 1, null, false, false));

        assertThat(postedInterest.isGreaterThanZero()).isTrue();
        assertThat(projectedInterest.getAmount()).isEqualByComparingTo(postedInterest.getAmount());
    }

    @Test
    void projectionLeavesTheStoredBalanceFieldsUntouched() {
        SavingsAccount account = accountWith(LocalDate.of(2026, 8, 11));

        account.projectInterestUsing(MC, UP_TO, false, 1);

        assertThat(account.retrieveListOfTransactions()).allSatisfy(transaction -> {
            assertThat(ReflectionTestUtils.getField(transaction, "balanceEndDate")).isNull();
            assertThat(ReflectionTestUtils.getField(transaction, "runningBalance")).isNull();
        });
    }

    @Test
    void projectionIgnoresTransactionsAfterItsDate() {
        SavingsAccount withLaterWithdrawal = accountWith(LocalDate.of(2026, 8, 15));
        SavingsAccount withoutIt = accountWith(null);

        Money withLater = interestOf(withLaterWithdrawal.projectInterestUsing(MC, LocalDate.of(2026, 8, 12), false, 1));
        Money without = interestOf(withoutIt.projectInterestUsing(MC, LocalDate.of(2026, 8, 12), false, 1));

        assertThat(withLater.getAmount()).isEqualByComparingTo(without.getAmount());
    }

    @Test
    void reversingAccrualsStepsTheAccruedTillDateBack() {
        SavingsAccount account = accountWith(null);
        account.addTransaction(
                SavingsAccountTransaction.accrual(account, office, LocalDate.of(2026, 8, 15), Money.of(NGN, BigDecimal.ONE), false, null));
        account.setAccruedTillDate(LocalDate.of(2026, 8, 20));

        account.reverseAccrualsFrom(LocalDate.of(2026, 8, 12), false);

        assertThat(ReflectionTestUtils.getField(account, "accruedTillDate")).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    private SavingsAccount accountWith(final LocalDate withdrawalDate) {
        SavingsAccount account = new SavingsAccount();
        ReflectionTestUtils.setField(account, "currency", NGN);
        ReflectionTestUtils.setField(account, "activatedOnDate", ACTIVATION);
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRate", BigDecimal.valueOf(12));
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRateOverdraft", BigDecimal.ZERO);
        ReflectionTestUtils.setField(account, "minOverdraftForInterestCalculation", BigDecimal.ZERO);
        ReflectionTestUtils.setField(account, "interestCompoundingPeriodType", 1);
        ReflectionTestUtils.setField(account, "interestPostingPeriodType", 4);
        ReflectionTestUtils.setField(account, "interestCalculationType", 1);
        ReflectionTestUtils.setField(account, "interestCalculationDaysInYearType", 365);
        ReflectionTestUtils.setField(account, "summary", new SavingsAccountSummary());
        account.setHelpers(new SavingsAccountTransactionSummaryWrapper(),
                new SavingsHelper(mock(AccountTransfersReadPlatformService.class)));
        account.addTransaction(
                SavingsAccountTransaction.deposit(account, office, null, ACTIVATION, Money.of(NGN, BigDecimal.valueOf(100_000)), null));
        if (withdrawalDate != null) {
            account.addTransaction(SavingsAccountTransaction.withdrawal(account, office, null, withdrawalDate,
                    Money.of(NGN, BigDecimal.valueOf(40_000)), null));
        }
        return account;
    }

    private static Money interestOf(final List<PostingPeriod> periods) {
        Money total = Money.zero(NGN);
        for (PostingPeriod period : periods) {
            total = total.plus(period.getInterestEarned());
        }
        return total;
    }
}
