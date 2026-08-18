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
package org.apache.fineract.portfolio.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SavingsTransactionBooleanValuesTest {

    private static final BigDecimal TRANSACTION_AMOUNT = new BigDecimal("5500.00");

    private static SavingsTransactionBooleanValues withdrawal(BigDecimal chargeableAmount) {
        return new SavingsTransactionBooleanValues(false, true, true, false, false, chargeableAmount);
    }

    @Nested
    class ChargeableAmountAbsent {

        @Test
        void legacyConstructorKeepsHistoricalBehavior() {
            SavingsTransactionBooleanValues values = new SavingsTransactionBooleanValues(false, true, true, false, false);

            assertThat(values.isApplyWithdrawFee()).isTrue();
            assertThat(values.chargeableAmount()).isNull();
            assertThat(values.withdrawalFeeBase(TRANSACTION_AMOUNT)).isEqualByComparingTo(TRANSACTION_AMOUNT);
        }

        @Test
        void nullChargeableAmountChargesOnTransactionAmount() {
            SavingsTransactionBooleanValues values = withdrawal(null);

            assertThat(values.isApplyWithdrawFee()).isTrue();
            assertThat(values.withdrawalFeeBase(TRANSACTION_AMOUNT)).isEqualByComparingTo(TRANSACTION_AMOUNT);
        }
    }

    @Nested
    class ChargeableAmountZero {

        @Test
        void zeroDisablesWithdrawalFeesEntirely() {
            SavingsTransactionBooleanValues values = withdrawal(BigDecimal.ZERO);

            assertThat(values.isApplyWithdrawFee()).isFalse();
        }

        @Test
        void zeroWithScaleAlsoDisablesWithdrawalFees() {
            SavingsTransactionBooleanValues values = withdrawal(new BigDecimal("0.00"));

            assertThat(values.isApplyWithdrawFee()).isFalse();
        }
    }

    @Nested
    class ChargeableAmountPositive {

        @Test
        void positiveBaseIsUsedForFeeCalculationInsteadOfTransactionAmount() {
            SavingsTransactionBooleanValues values = withdrawal(new BigDecimal("5000.00"));

            assertThat(values.isApplyWithdrawFee()).isTrue();
            assertThat(values.withdrawalFeeBase(TRANSACTION_AMOUNT)).isEqualByComparingTo(new BigDecimal("5000.00"));
        }
    }

    @Test
    void feeDisabledByCallerStaysDisabledRegardlessOfBase() {
        SavingsTransactionBooleanValues values = new SavingsTransactionBooleanValues(false, true, false, false, false,
                new BigDecimal("5000.00"));

        assertThat(values.isApplyWithdrawFee()).isFalse();
    }
}
