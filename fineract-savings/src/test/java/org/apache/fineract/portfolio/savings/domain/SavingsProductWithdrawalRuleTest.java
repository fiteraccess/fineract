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

import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.QualifyingWithdrawalType;
import org.apache.fineract.portfolio.savings.data.SavingsProductWithdrawalRuleData;
import org.junit.jupiter.api.Test;

class SavingsProductWithdrawalRuleTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void aProductWithoutRulesRunsOnTheDefaultsThisMonth() {
        SavingsProductWithdrawalRule rule = SavingsProductWithdrawalRule.inEffectOn(List.of(), TODAY);

        assertThat(rule).isEqualTo(SavingsProductWithdrawalRule.defaults(OCTOBER));
        assertThat(rule.monthlyWithdrawalThreshold()).isEqualTo(4);
        assertThat(rule.qualifyingWithdrawalTypes()).containsExactlyElementsOf(QualifyingWithdrawalType.DEFAULTS);
    }

    @Test
    void theDefaultsLeaveCashCardAndAtmWithdrawalsUncounted() {
        assertThat(SavingsProductWithdrawalRule.defaults(OCTOBER).qualifyingWithdrawalTypes()).doesNotContain(
                QualifyingWithdrawalType.CASH_WITHDRAWAL, QualifyingWithdrawalType.CARD_PURCHASE, QualifyingWithdrawalType.ATM_WITHDRAWAL);
    }

    @Test
    void anOptInTypeIsParsedFromItsCode() {
        assertThat(SavingsProductWithdrawalRule.parseTypes(List.of("cash_withdrawal", "ATM_WITHDRAWAL", "CARD_PURCHASE"))).containsExactly(
                QualifyingWithdrawalType.CASH_WITHDRAWAL, QualifyingWithdrawalType.ATM_WITHDRAWAL, QualifyingWithdrawalType.CARD_PURCHASE);
    }

    @Test
    void aNextMonthRuleIsListedAfterTheOneInEffect() {
        SavingsProductWithdrawalRule current = rule(OCTOBER, 4);
        SavingsProductWithdrawalRule next = rule(NOVEMBER, 2);

        assertThat(SavingsProductWithdrawalRule.inEffectOn(List.of(current, next), TODAY)).isEqualTo(current);
        assertThat(SavingsProductWithdrawalRule.currentAndFuture(List.of(next, rule(LocalDate.of(2026, 9, 1), 6), current), TODAY))
                .containsExactly(current, next);
    }

    @Test
    void theRuleTakesEffectOnTheFirstOfItsMonth() {
        SavingsProductWithdrawalRule next = rule(NOVEMBER, 2);

        assertThat(SavingsProductWithdrawalRule.inEffectOn(List.of(rule(OCTOBER, 4), next), NOVEMBER)).isEqualTo(next);
    }

    @Test
    void anUpdateKeepsTheTermsItDoesNotMention() {
        SavingsProductWithdrawalRule current = new SavingsProductWithdrawalRule(OCTOBER, true, 4,
                List.of(QualifyingWithdrawalType.AIRTIME, QualifyingWithdrawalType.DATA));

        SavingsProductWithdrawalRule requested = current.withChangesFrom(command("{\"monthlyWithdrawalThreshold\": 6}"), NOVEMBER);

        assertThat(requested).isEqualTo(new SavingsProductWithdrawalRule(NOVEMBER, true, 6,
                List.of(QualifyingWithdrawalType.AIRTIME, QualifyingWithdrawalType.DATA)));
    }

    @Test
    void typesInAnyOrderOrRepeatedHaveTheSameTerms() {
        SavingsProductWithdrawalRule current = new SavingsProductWithdrawalRule(OCTOBER, true, 4,
                List.of(QualifyingWithdrawalType.AIRTIME, QualifyingWithdrawalType.DATA));

        SavingsProductWithdrawalRule requested = current
                .withChangesFrom(command("{\"qualifyingWithdrawalTypes\": [\"data\", \"AIRTIME\", \"DATA\"]}"), NOVEMBER);

        assertThat(requested.hasSameTermsAs(current)).isTrue();
    }

    @Test
    void theApiViewUsesAPlainDateString() {
        SavingsProductWithdrawalRuleData data = new SavingsProductWithdrawalRule(NOVEMBER, false, 0,
                List.of(QualifyingWithdrawalType.BILL_PAYMENT)).toData();

        assertThat(data).isEqualTo(new SavingsProductWithdrawalRuleData("2026-11-01", false, 0, List.of("BILL_PAYMENT")));
    }

    @Test
    void theStoredCsvRoundTrips() {
        SavingsProductWithdrawalRule rule = SavingsProductWithdrawalRule.defaults(OCTOBER);

        assertThat(SavingsProductWithdrawalRule.parseTypesCsv(rule.typesCsv())).isEqualTo(rule.qualifyingWithdrawalTypes());
    }

    private static SavingsProductWithdrawalRule rule(final LocalDate effectiveFrom, final int threshold) {
        return new SavingsProductWithdrawalRule(effectiveFrom, true, threshold, List.of(QualifyingWithdrawalType.values()));
    }

    private static JsonCommand command(final String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), null, 1L, null, null, null, null, null, null,
                null, 1L, null, null, null, null);
    }
}
