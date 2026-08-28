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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountClosureTransferTest {

    @Nested
    class Parsing {

        @Test
        void anOrdinaryWithdrawalIsNotAClosure() {
            assertThat(parse("""
                    { "transactionAmount": 500, "transactionDate": "2026-08-28" }
                    """).requested()).isFalse();
        }

        @Test
        void anExplicitlyFalseFlagIsNotAClosure() {
            assertThat(parse("""
                    { "transactionAmount": 500, "isAccountClosureTransfer": false }
                    """).requested()).isFalse();
        }

        @Test
        void readsTheNestedClosurePayload() {
            AccountClosureTransfer transfer = parse("""
                    {
                      "transactionAmount": 15000.00,
                      "isAccountClosureTransfer": true,
                      "closure": { "closedOnDate": "28 August 2026", "withdrawBalance": false,
                                   "note": "Customer requested", "dateFormat": "dd MMMM yyyy", "locale": "en" }
                    }
                    """);

            assertThat(transfer.requested()).isTrue();
            assertThat(transfer.closurePayload().getAsJsonObject().get("closedOnDate").getAsString()).isEqualTo("28 August 2026");
        }

        @Test
        void rejectsAClosureWithNoPayload() {
            assertInvalid("""
                    { "transactionAmount": 15000.00, "isAccountClosureTransfer": true }
                    """);
        }

        @Test
        void rejectsAClosurePayloadWithoutAClosedOnDate() {
            assertInvalid("""
                    { "transactionAmount": 15000.00, "isAccountClosureTransfer": true,
                      "closure": { "note": "no date" } }
                    """);
        }

        @Test
        void rejectsANonObjectClosurePayload() {
            assertInvalid("""
                    { "transactionAmount": 15000.00, "isAccountClosureTransfer": true, "closure": "28 August 2026" }
                    """);
        }
    }

    @Nested
    class BalanceAssertion {

        private final AccountClosureTransfer transfer = parse("""
                { "isAccountClosureTransfer": true, "closure": { "closedOnDate": "28 August 2026" } }
                """);

        @Test
        void passesWhenTheDebitRetiresTheWholeBalance() {
            assertThatCode(() -> transfer.assertClearsBalance(new BigDecimal("15000.00"), new BigDecimal("15000.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        void ignoresScaleDifferences() {
            assertThatCode(() -> transfer.assertClearsBalance(new BigDecimal("15000"), new BigDecimal("15000.000000")))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsWhenFineractHasNotCaughtUpYet() {
            assertThatThrownBy(() -> transfer.assertClearsBalance(new BigDecimal("15000.00"), new BigDecimal("15250.00")))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class).hasMessageContaining("15250.00");
        }

        @Test
        void reportsARetriableCodeSoTheCallerKnowsToWait() {
            assertThatThrownBy(() -> transfer.assertClearsBalance(new BigDecimal("10"), new BigDecimal("20"))).isInstanceOfSatisfying(
                    GeneralPlatformDomainRuleException.class,
                    e -> assertThat(e.getGlobalisationMessageCode()).isEqualTo(AccountClosureTransfer.BALANCE_MISMATCH_CODE));
        }

        @Test
        void treatsAbsentAmountsAsZero() {
            assertThatCode(() -> transfer.assertClearsBalance(null, null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> transfer.assertClearsBalance(null, new BigDecimal("5")))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class);
        }
    }

    private static void assertInvalid(final String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    private static AccountClosureTransfer parse(final String json) {
        FromJsonHelper fromJsonHelper = new FromJsonHelper();
        return AccountClosureTransfer.parse(new JsonCommand(1L, fromJsonHelper.parse(json), fromJsonHelper));
    }
}
