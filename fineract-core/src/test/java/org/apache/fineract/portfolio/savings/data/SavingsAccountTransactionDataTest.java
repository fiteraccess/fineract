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
package org.apache.fineract.portfolio.savings.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsAccountTransactionDataTest {

    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, null);
    // September's running balances include August's posted interest; the compounding carry already counts it.
    private static final BigDecimal AUGUST_INTEREST = new BigDecimal("837.35");

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
    void aDepositStepsFromTheOpeningBalanceNotTheRunningBalanceThatHoldsPostedInterest() {
        SavingsAccountTransactionData deposit = transaction(SavingsAccountTransactionType.DEPOSIT, "1000.00",
                new BigDecimal("44347.40").add(AUGUST_INTEREST));

        assertThat(deposit.toEndOfDayBalance(money("43347.40")).closingBalance().getAmount()).isEqualByComparingTo("44347.40");
    }

    @Test
    void aWithdrawalStepsFromTheOpeningBalanceNotTheRunningBalanceThatHoldsPostedInterest() {
        SavingsAccountTransactionData withdrawal = transaction(SavingsAccountTransactionType.WITHDRAWAL, "100.00",
                new BigDecimal("43247.40").add(AUGUST_INTEREST));

        assertThat(withdrawal.toEndOfDayBalance(money("43347.40")).closingBalance().getAmount()).isEqualByComparingTo("43247.40");
    }

    @Test
    void anyOtherDebitMovesTheBalanceTheWayTheRunningBalanceDoes() {
        SavingsAccountTransactionData tax = transaction(SavingsAccountTransactionType.WITHHOLD_TAX, "8.00",
                new BigDecimal("43339.40").add(AUGUST_INTEREST));

        assertThat(tax.toEndOfDayBalance(money("43347.40")).closingBalance().getAmount()).isEqualByComparingTo("43339.40");
    }

    @Test
    void aManualPostingReadForThePostingEngineStaysManual() {
        SavingsAccountTransactionEnumData typeData = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        LocalDate date = LocalDate.of(2026, 10, 2);

        SavingsAccountTransactionData posting = SavingsAccountTransactionData.create(1L, typeData, null, 1L, "SA-1", date, null,
                new BigDecimal("28.03"), null, null, false, date, false, null, date, false, null, true);

        assertThat(posting.isManualTransaction()).isTrue();
    }

    private static Money money(String amount) {
        return Money.of(NGN, new BigDecimal(amount));
    }

    private static SavingsAccountTransactionData transaction(SavingsAccountTransactionType type, String amount, BigDecimal runningBalance) {
        SavingsAccountTransactionEnumData typeData = new SavingsAccountTransactionEnumData(type.getValue().longValue(), type.getCode(),
                type.getValue().toString());
        LocalDate date = LocalDate.of(2026, 9, 1);
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(1L, typeData, null, 1L, "SA-1", date, null,
                new BigDecimal(amount), null, runningBalance, false, date, false, null, date);
        tx.updateCumulativeBalanceAndDates(NGN, date);
        return tx;
    }
}
