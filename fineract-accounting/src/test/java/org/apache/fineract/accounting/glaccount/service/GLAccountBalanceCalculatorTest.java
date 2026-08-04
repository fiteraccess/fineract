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
package org.apache.fineract.accounting.glaccount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class GLAccountBalanceCalculatorTest {

    private static final BigDecimal DEBITS = new BigDecimal("700.000000");
    private static final BigDecimal CREDITS = new BigDecimal("250.000000");

    @Nested
    @DisplayName("sign convention")
    class SignConvention {

        /**
         * Restates the rule in {@code JournalEntryRunningBalanceUpdateServiceImpl.calculateRunningBalance}, which
         * decides per-entry whether a posting increases or decreases the account. If either side is ever edited alone,
         * this table stops matching the other and the change gets noticed in review.
         */
        @ParameterizedTest(name = "{0} increases on debit = {1}")
        @CsvSource({ "ASSET,true", "EXPENSE,true", "LIABILITY,false", "EQUITY,false", "INCOME,false" })
        void appliesFineractRunningBalanceRule(final GLAccountType type, final boolean increasesOnDebit) {
            final BigDecimal expected = increasesOnDebit ? DEBITS.subtract(CREDITS) : CREDITS.subtract(DEBITS);

            assertThat(GLAccountBalanceCalculator.signedNet(type, DEBITS, CREDITS)).isEqualByComparingTo(expected);
        }

        @Test
        void signsAssetAsDebitPositive() {
            assertThat(GLAccountBalanceCalculator.signedNet(GLAccountType.ASSET, DEBITS, CREDITS))
                    .isEqualByComparingTo(new BigDecimal("450.000000"));
        }

        @Test
        void signsLiabilityAsCreditPositive() {
            assertThat(GLAccountBalanceCalculator.signedNet(GLAccountType.LIABILITY, DEBITS, CREDITS))
                    .isEqualByComparingTo(new BigDecimal("-450.000000"));
        }

        @ParameterizedTest
        @EnumSource(GLAccountType.class)
        void treatsEqualDebitsAndCreditsAsZeroForEveryType(final GLAccountType type) {
            assertThat(GLAccountBalanceCalculator.signedNet(type, DEBITS, DEBITS)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * A generic property of the identity, not reversal-specific: reversed entries are excluded before they ever
         * reach this calculator (see {@code GLAccountBalanceReadPlatformServiceImplTest}), so this only pins that equal
         * debits and credits net to zero regardless of which side "wins" for the account type.
         */
        @ParameterizedTest
        @EnumSource(GLAccountType.class)
        void nettesEqualDebitsAndCreditsToZero(final GLAccountType type) {
            final BigDecimal amount = new BigDecimal("1250.750000");

            assertThat(GLAccountBalanceCalculator.signedNet(type, amount, amount)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @ParameterizedTest
        @EnumSource(GLAccountType.class)
        void treatsNullsAsZeroForEveryType(final GLAccountType type) {
            assertThat(GLAccountBalanceCalculator.signedNet(type, null, null)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("money normalisation")
    class MoneyNormalisation {

        @Test
        void normalisesToTheColumnScale() {
            assertThat(GLAccountBalanceCalculator.money(BigDecimal.ZERO).scale()).isEqualTo(GLAccountBalanceCalculator.MONEY_SCALE);
            assertThat(GLAccountBalanceCalculator.money(new BigDecimal("12.5")).scale()).isEqualTo(GLAccountBalanceCalculator.MONEY_SCALE);
        }

        @Test
        void treatsNullAsZero() {
            assertThat(GLAccountBalanceCalculator.money(null)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * The regression guard for the {@code Long}-typed {@code organizationRunningBalance} on the legacy
         * chart-of-accounts path, which truncates the sub-unit component of a {@code decimal(19,6)} column.
         */
        @Test
        void preservesSubUnitPrecision() {
            assertThat(GLAccountBalanceCalculator.money(new BigDecimal("0.000001"))).isEqualByComparingTo(new BigDecimal("0.000001"));
        }

        @Test
        void preservesPrecisionBeyondDoubleForALargeControlAccountBalance() {
            final BigDecimal debits = new BigDecimal("9999999999999.123456");
            final BigDecimal credits = new BigDecimal("0.000001");

            assertThat(GLAccountBalanceCalculator.signedNet(GLAccountType.ASSET, debits, credits))
                    .isEqualByComparingTo(new BigDecimal("9999999999999.123455"));
        }

        /**
         * Every input is a sum of {@code decimal(19,6)} values or a difference of two such sums, so rounding can never
         * legitimately be required. A scale beyond the column's would mean the schema had changed underneath us.
         */
        @Test
        void refusesToRoundAValueFinerThanTheColumnScale() {
            assertThatThrownBy(() -> GLAccountBalanceCalculator.money(new BigDecimal("0.0000001"))).isInstanceOf(ArithmeticException.class);
        }
    }
}
