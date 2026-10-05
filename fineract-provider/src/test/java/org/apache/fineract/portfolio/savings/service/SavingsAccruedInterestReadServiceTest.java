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
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.Test;

class SavingsAccruedInterestReadServiceTest {

    private static final LocalDate OPENED = LocalDate.of(2026, 9, 30);
    private static final LocalDate SETTLED = LocalDate.of(2026, 10, 1);

    @Test
    void aGoalOwesEverythingAccruedLessWhatPartialSettlementsPaid() {
        // Goal 9500000125 on Access Dev: 9.32 accrued since opening, 3.25 paid by a posting and two withdrawals.
        assertThat(SavingsAccruedInterestReadService.periodStart(true, OPENED, SETTLED)).isEqualTo(OPENED);
        assertThat(SavingsAccruedInterestReadService.owed(true, new BigDecimal("9.32"), new BigDecimal("3.25")))
                .isEqualByComparingTo("6.07");
    }

    @Test
    void aGoalSettledInFullOwesNothingRatherThanANegativeAmount() {
        assertThat(SavingsAccruedInterestReadService.owed(true, new BigDecimal("9.31"), new BigDecimal("9.32"))).isEqualByComparingTo("0");
    }

    @Test
    void anOverdraftInterestPostingClosesThePeriodLikeAnInterestPosting() {
        assertThat(SavingsAccruedInterestReadService.ACCOUNT_SQL)
                .contains("p.transaction_type_enum in (" + SavingsAccountTransactionType.INTEREST_POSTING.getValue() + ", "
                        + SavingsAccountTransactionType.OVERDRAFT_INTEREST.getValue() + ")");
    }

    @Test
    void anyOtherAccountsOpenPeriodStartsAfterItsLastPostingNotTheStoredPostedTillDate() {
        assertThat(SavingsAccruedInterestReadService.periodStart(false, OPENED, LocalDate.of(2026, 10, 31)))
                .isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(SavingsAccruedInterestReadService.periodStart(false, OPENED, null)).isEqualTo(OPENED);
        assertThat(SavingsAccruedInterestReadService.owed(false, new BigDecimal("2.99"), new BigDecimal("89.19")))
                .isEqualByComparingTo("2.99");
    }
}
