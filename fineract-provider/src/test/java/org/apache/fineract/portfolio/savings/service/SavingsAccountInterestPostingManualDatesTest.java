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

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.junit.jupiter.api.Test;

class SavingsAccountInterestPostingManualDatesTest {

    // "Post interest as on 16 November" with posting at the period end books the posting on 15 November.
    private static final LocalDate MANUAL_POSTING = LocalDate.of(2026, 11, 15);

    @Test
    void aManualPostingAtThePeriodEndSplitsTheMonthAtItsAsOnDate() {
        List<LocalDateInterval> periods = periodsWith(SavingsHelper.manualAsOnDates(List.of(MANUAL_POSTING), true));

        assertThat(periods).extracting(LocalDateInterval::endDate).containsExactly(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30));
    }

    @Test
    void aManualPostingDatedOnItsAsOnDateIsTakenAsIs() {
        assertThat(SavingsHelper.manualAsOnDates(List.of(MANUAL_POSTING), false)).containsExactly(MANUAL_POSTING);
    }

    private static List<LocalDateInterval> periodsWith(List<LocalDate> asOnDates) {
        return new SavingsHelper(null).determineInterestPostingPeriods(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                SavingsPostingInterestPeriodType.MONTHLY, 1, asOnDates);
    }
}
