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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The data-side summary recompute (used by the scheduled interest-posting path) must debit the same rows the entity
 * recompute does: signed-statement fees and the reference legs were left out and handed back to the balance.
 */
class SavingsAccountTransactionDataSummaryWrapperTest {

    private static final CurrencyData NGN = new CurrencyData("NGN", 2, null);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

    private final SavingsAccountTransactionDataSummaryWrapper wrapper = new SavingsAccountTransactionDataSummaryWrapper();
    private FineractPlatformTenant originalTenant;

    @BeforeEach
    void setUp() {
        originalTenant = ThreadLocalContextUtil.getTenant();
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        MoneyHelper.clearCache();
        MoneyHelper.initializeTenantRoundingMode("default", 4);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setTenant(originalTenant);
        MoneyHelper.clearCache();
    }

    @Test
    void withdrawalTotalCountsSignedStatementFeesButNotReversedOnes() {
        BigDecimal total = wrapper.calculateTotalWithdrawals(NGN,
                List.of(transaction(SavingsAccountTransactionType.WITHDRAWAL, "100.00", false),
                        transaction(SavingsAccountTransactionType.SIGNED_STATEMENT_FEE, "20.00", false),
                        transaction(SavingsAccountTransactionType.SIGNED_STATEMENT_FEE, "20.00", true)));

        assertThat(total).isEqualByComparingTo("120.00");
    }

    @Test
    void otherDebitsLeaveOutWhatTheNamedTotalsAlreadyCount() {
        BigDecimal total = wrapper.calculateTotalOtherDebits(NGN,
                List.of(transaction(SavingsAccountTransactionType.WITHDRAWAL, "100.00", false),
                        transaction(SavingsAccountTransactionType.SIGNED_STATEMENT_FEE, "20.00", false),
                        transaction(SavingsAccountTransactionType.DEPOSIT, "500.00", false),
                        transaction(SavingsAccountTransactionType.COMMISSION, "8.00", false),
                        transaction(SavingsAccountTransactionType.VAT, "0.45", false),
                        transaction(SavingsAccountTransactionType.WITHDRAWAL_FEE, "5.00", false)));

        // commission 8 + VAT 0.45 + withdrawal fee 5; the signed-statement fee is in the withdrawal total
        assertThat(total).isEqualByComparingTo("13.45");
    }

    @Test
    void fullRecomputeDebitsEveryStandingDebitExactlyOnce() {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(NGN, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);

        summary.updateSummary(NGN, wrapper,
                List.of(transaction(SavingsAccountTransactionType.DEPOSIT, "1000.00", false),
                        transaction(SavingsAccountTransactionType.WITHDRAWAL, "100.00", false),
                        transaction(SavingsAccountTransactionType.BILL_PAYMENT, "50.00", false),
                        transaction(SavingsAccountTransactionType.ANNUAL_FEE, "10.00", false),
                        transaction(SavingsAccountTransactionType.SIGNED_STATEMENT_FEE, "20.00", false),
                        transaction(SavingsAccountTransactionType.COMMISSION, "8.00", false),
                        transaction(SavingsAccountTransactionType.VAT, "0.45", false),
                        transaction(SavingsAccountTransactionType.VAT, "0.45", true)));

        // 1000 - 100 - 50 - 10 - 20 - 8 - 0.45
        assertThat(summary.getAccountBalance()).isEqualByComparingTo("811.55");
    }

    private static SavingsAccountTransactionData transaction(final SavingsAccountTransactionType type, final String amount,
            final boolean reversed) {
        SavingsAccountTransactionEnumData typeData = new SavingsAccountTransactionEnumData(type.getValue().longValue(), type.getCode(),
                type.getValue().toString());
        return SavingsAccountTransactionData.create(1L, typeData, null, 1L, "SA-1", DATE, null, new BigDecimal(amount), null, null,
                reversed, DATE, false, null, DATE);
    }
}
