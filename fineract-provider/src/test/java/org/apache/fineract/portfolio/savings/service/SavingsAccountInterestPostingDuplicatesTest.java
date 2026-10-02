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
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.junit.jupiter.api.Test;

class SavingsAccountInterestPostingDuplicatesTest {

    private static final LocalDate AUG_END = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEPT_END = LocalDate.of(2026, 9, 30);

    @Test
    void everyOtherLivePostingInThePeriodsWindowIsReversedInThisRun() {
        SavingsAccountData account = account();
        SavingsAccountTransactionData kept = add(account, 21423L, SavingsAccountTransactionType.INTEREST_POSTING, SEPT_END, false, false);
        SavingsAccountTransactionData sameDay = add(account, 21489L, SavingsAccountTransactionType.INTEREST_POSTING, SEPT_END, false,
                false);
        // Dated by the old next-day posting setting: August's interest booked on the first of September.
        SavingsAccountTransactionData misdated = add(account, 17001L, SavingsAccountTransactionType.INTEREST_POSTING, AUG_END.plusDays(1),
                false, false);
        SavingsAccountTransactionData august = add(account, 21413L, SavingsAccountTransactionType.INTEREST_POSTING, AUG_END, false, false);

        assertThat(SavingsAccountInterestPostingServiceImpl.reverseDuplicatePostings(kept, AUG_END, SEPT_END, false, account)).isTrue();

        assertThat(kept.isReversed()).isFalse();
        assertThat(sameDay.isReversedInRun()).isTrue();
        assertThat(misdated.isReversedInRun()).isTrue();
        assertThat(august.isReversed()).isFalse();
    }

    @Test
    void manualReversedAndOtherKindsAreLeftAlone() {
        SavingsAccountData account = account();
        SavingsAccountTransactionData kept = add(account, 1L, SavingsAccountTransactionType.INTEREST_POSTING, SEPT_END, false, false);
        SavingsAccountTransactionData manual = add(account, 2L, SavingsAccountTransactionType.INTEREST_POSTING, SEPT_END.minusDays(5),
                false, true);
        SavingsAccountTransactionData reversedEarlier = add(account, 3L, SavingsAccountTransactionType.INTEREST_POSTING, SEPT_END, true,
                false);
        SavingsAccountTransactionData tax = add(account, 4L, SavingsAccountTransactionType.WITHHOLD_TAX, SEPT_END, false, false);
        SavingsAccountTransactionData overdraft = add(account, 5L, SavingsAccountTransactionType.OVERDRAFT_INTEREST, SEPT_END, false,
                false);

        assertThat(SavingsAccountInterestPostingServiceImpl.reverseDuplicatePostings(kept, AUG_END, SEPT_END, false, account)).isFalse();

        assertThat(manual.isReversed()).isFalse();
        assertThat(reversedEarlier.isReversedInRun()).isFalse();
        assertThat(tax.isReversed()).isFalse();
        assertThat(overdraft.isReversed()).isFalse();
    }

    @Test
    void aPeriodWithNoPostingOnItsDateClearsItsWindowSoTheEngineCanPostAfresh() {
        SavingsAccountData account = account();
        SavingsAccountTransactionData misdated = add(account, 7L, SavingsAccountTransactionType.INTEREST_POSTING, AUG_END.plusDays(1),
                false, false);

        assertThat(SavingsAccountInterestPostingServiceImpl.reverseDuplicatePostings(null, AUG_END, SEPT_END, false, account)).isTrue();

        assertThat(misdated.isReversedInRun()).isTrue();
    }

    @Test
    void theFirstWindowWithThePivotIsJustThePostingDate() {
        SavingsAccountData account = account();
        SavingsAccountTransactionData kept = add(account, 1L, SavingsAccountTransactionType.INTEREST_POSTING, SEPT_END, false, false);
        SavingsAccountTransactionData postedTill = add(account, 2L, SavingsAccountTransactionType.INTEREST_POSTING, AUG_END, false, false);

        assertThat(SavingsAccountInterestPostingServiceImpl.reverseDuplicatePostings(kept, SEPT_END.minusDays(1), SEPT_END, false, account))
                .isFalse();

        assertThat(postedTill.isReversed()).isFalse();
    }

    private static SavingsAccountTransactionData add(SavingsAccountData account, Long id, SavingsAccountTransactionType type,
            LocalDate date, boolean reversed, boolean manual) {
        SavingsAccountTransactionEnumData typeData = new SavingsAccountTransactionEnumData(type.getValue().longValue(), type.getCode(),
                type.getValue().toString());
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(id, typeData, null, 1L, "SA-1", date, null,
                new BigDecimal("64789.45"), null, null, reversed, null, false, null, null, date);
        if (manual) {
            markManual(tx);
        }
        account.setSavingsAccountTransactionData(tx);
        return tx;
    }

    private static void markManual(SavingsAccountTransactionData tx) {
        try {
            java.lang.reflect.Field field = SavingsAccountTransactionData.class.getDeclaredField("isManualTransaction");
            field.setAccessible(true);
            field.setBoolean(tx, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SavingsAccountData account() {
        return SavingsAccountData.instance(1L, "SA-1", null, "EXT-1", null, null, null, null, null, null, null, null, null, null, null,
                null, new CurrencyData("NGN"), null, null, null, null, null, null, null, null, false, null, false, null, null, false, null,
                false, null, null, null, null, false, null, null, false, null, null, null, null);
    }
}
