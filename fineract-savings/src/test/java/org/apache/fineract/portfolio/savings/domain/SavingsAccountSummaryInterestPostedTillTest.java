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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AB-401: a catch-up posting run sends two periods to Synapse, which may replay September before August; the posted
 * till date must stay on September or the next run and the accrued-interest read restart from August.
 */
class SavingsAccountSummaryInterestPostedTillTest {

    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, null);
    private static final LocalDate AUGUST_END = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEPTEMBER_END = LocalDate.of(2026, 9, 30);

    private final SavingsAccountTransactionSummaryWrapper wrapper = new SavingsAccountTransactionSummaryWrapper();
    private final Office office = mock(Office.class);

    @BeforeEach
    void setBusinessDate() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, SEPTEMBER_END.plusDays(1))));
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());
    }

    @AfterEach
    void resetThreadLocalContext() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCache();
    }

    @Test
    void anOlderPeriodReplayedLastKeepsTheLaterPostedTillDate() {
        SavingsAccount account = account();
        SavingsAccountSummary summary = new SavingsAccountSummary();

        summary.updateSummaryWithTransaction(NGN, wrapper, posting(account, SEPTEMBER_END));
        summary.updateSummaryWithTransaction(NGN, wrapper, posting(account, AUGUST_END));

        assertThat(summary.getInterestPostedTillDate()).isEqualTo(SEPTEMBER_END);
    }

    @Test
    void aLaterPeriodAdvancesThePostedTillDate() {
        SavingsAccount account = account();
        SavingsAccountSummary summary = new SavingsAccountSummary();

        summary.updateSummaryWithTransaction(NGN, wrapper, posting(account, AUGUST_END));
        summary.updateSummaryWithTransaction(NGN, wrapper, posting(account, SEPTEMBER_END));

        assertThat(summary.getInterestPostedTillDate()).isEqualTo(SEPTEMBER_END);
    }

    private SavingsAccount account() {
        SavingsAccount account = new SavingsAccount();
        ReflectionTestUtils.setField(account, "currency", NGN);
        return account;
    }

    private SavingsAccountTransaction posting(final SavingsAccount account, final LocalDate date) {
        return SavingsAccountTransaction.interestPosting(account, office, date, Money.of(NGN, BigDecimal.TEN), false);
    }
}
