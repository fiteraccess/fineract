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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.junit.jupiter.api.Test;

class SavingsInterestSummaryTest {

    private static final Integer ACCRUAL = AccountingRuleType.ACCRUAL_PERIODIC.getValue();
    private static final Integer CASH = AccountingRuleType.CASH_BASED.getValue();

    // Access Dev 9400000012 on 7 Oct 2026: a stale stored earned total showed "overdraft interest not posted" -380.94.
    @Test
    void anAccrualAccountReadsWhatIsOwedFromItsAccrualsNotTheStaleEarnedTotal() {
        SavingsInterestSummary summary = SavingsInterestSummary.of(ACCRUAL, false, amount("963.17"), amount("1344.11"), BigDecimal.ZERO,
                amount("115.43"));

        assertThat(summary.notPosted()).isEqualByComparingTo("115.43");
        assertThat(summary.earned()).isEqualByComparingTo("1459.54");
    }

    @Test
    void anAccrualAccountWithNothingAccruedSinceItsPostingHasNothingOutstanding() {
        SavingsInterestSummary summary = SavingsInterestSummary.of(ACCRUAL, false, null, amount("555.12"), BigDecimal.ZERO, null);

        assertThat(summary.notPosted()).isEqualByComparingTo("0");
        assertThat(summary.earned()).isEqualByComparingTo("555.12");
    }

    @Test
    void aGoalIsOwedItsAccrualsLessWhatSettlementsAlreadyPaid() {
        SavingsInterestSummary summary = SavingsInterestSummary.of(ACCRUAL, true, amount("1.37"), amount("6.85"), BigDecimal.ZERO,
                amount("9.32"));

        assertThat(summary.notPosted()).isEqualByComparingTo("2.47");
        assertThat(summary.earned()).isEqualByComparingTo("9.32");
    }

    @Test
    void aGoalPaidBeyondItsAccrualsOwesNothingRatherThanANegative() {
        SavingsInterestSummary summary = SavingsInterestSummary.of(ACCRUAL, true, null, amount("6.85"), BigDecimal.ZERO, amount("4.00"));

        assertThat(summary.notPosted()).isEqualByComparingTo("0");
    }

    @Test
    void anAccountNeverCreditedShowsNoEarnedFigure() {
        SavingsInterestSummary summary = SavingsInterestSummary.of(ACCRUAL, false, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(summary.earned()).isNull();
    }

    @Test
    void aNonAccrualProductKeepsTheStoredCalculation() {
        SavingsInterestSummary summary = SavingsInterestSummary.of(CASH, false, amount("40.00"), amount("30.00"), amount("5.00"),
                amount("99.00"));

        assertThat(summary.earned()).isEqualByComparingTo("40.00");
        assertThat(summary.notPosted()).isEqualByComparingTo("15.00");
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
