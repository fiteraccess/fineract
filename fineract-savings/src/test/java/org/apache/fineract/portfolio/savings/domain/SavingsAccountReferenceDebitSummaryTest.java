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
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AB-540: the summary's reference-debit subtraction must cover every type {@code applyReferenceTransactions} can
 * create. {@code CONVENIENCE_FEE} was created but never subtracted, so its amount vanished from
 * {@code account_balance_derived} on the next full recompute — ₦100 on a restored Access Dev account, surfaced by
 * replaying a backdated NIP withdrawal.
 */
class SavingsAccountReferenceDebitSummaryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);
    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, null);

    private final SavingsAccountTransactionSummaryWrapper wrapper = new SavingsAccountTransactionSummaryWrapper();

    @BeforeEach
    void setBusinessDate() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, DATE)));
        // Money.of consults MoneyHelper, which refuses to work without a tenant context.
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());
    }

    @AfterEach
    void resetThreadLocalContext() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCache();
    }

    @Test
    void everyReferenceDebitTypeIsSubtracted() {
        assertThat(SavingsAccountTransactionType.EMT_LEVY.isReferenceDebit()).isTrue();
        assertThat(SavingsAccountTransactionType.COMMISSION.isReferenceDebit()).isTrue();
        assertThat(SavingsAccountTransactionType.VAT.isReferenceDebit()).isTrue();
        assertThat(SavingsAccountTransactionType.CONVENIENCE_FEE.isReferenceDebit()).isTrue();
    }

    @Test
    void primaryLegsAreNotReferenceDebits() {
        assertThat(SavingsAccountTransactionType.WITHDRAWAL.isReferenceDebit()).isFalse();
        assertThat(SavingsAccountTransactionType.BILL_PAYMENT.isReferenceDebit()).isFalse();
        assertThat(SavingsAccountTransactionType.DEPOSIT.isReferenceDebit()).isFalse();
    }

    @Test
    void totalIncludesTheConvenienceFeeThatUsedToVanish() {
        var transactions = List.of(emtLevy("50.00"), commission("8.00"), vat("0.45"), convenienceFee("100.00"));

        var total = wrapper.calculateTotalReferenceDebits(NGN, transactions);

        assertThat(total).isEqualByComparingTo(new BigDecimal("158.45"));
    }

    @Test
    void reversedLegsAreExcluded() {
        var reversed = convenienceFee("100.00");
        reversed.reverse();

        var total = wrapper.calculateTotalReferenceDebits(NGN, List.of(commission("8.00"), reversed));

        assertThat(total).isEqualByComparingTo(new BigDecimal("8.00"));
    }

    private SavingsAccountTransaction emtLevy(String amount) {
        return SavingsAccountTransaction.emtLevy(account(), office(), DATE, money(amount), "ref");
    }

    private SavingsAccountTransaction commission(String amount) {
        return SavingsAccountTransaction.commission(account(), office(), DATE, money(amount), "ref", "NIBSS", null, null);
    }

    private SavingsAccountTransaction vat(String amount) {
        return SavingsAccountTransaction.vat(account(), office(), DATE, money(amount), "ref", "NIBSS");
    }

    private SavingsAccountTransaction convenienceFee(String amount) {
        return SavingsAccountTransaction.convenienceFee(account(), office(), DATE, money(amount), "ref", "CORALPAY");
    }

    private static Money money(String amount) {
        return Money.of(NGN, new BigDecimal(amount));
    }

    private static SavingsAccount account() {
        return mock(SavingsAccount.class);
    }

    private static Office office() {
        Office office = mock(Office.class);
        when(office.getId()).thenReturn(1L);
        return office;
    }
}
