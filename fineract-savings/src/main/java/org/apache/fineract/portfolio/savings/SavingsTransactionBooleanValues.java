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

import java.math.BigDecimal;

public class SavingsTransactionBooleanValues {

    private final boolean isAccountTransfer;
    private final boolean isRegularTransaction;
    private final boolean isApplyWithdrawFee;
    private final boolean isInterestTransfer;
    private final boolean isExceptionForBalanceCheck;
    private final BigDecimal chargeableAmount;

    public SavingsTransactionBooleanValues(final boolean isAccountTransfer, final boolean isRegularTransaction,
            final boolean isApplyWithdrawFee, final boolean isInterestTransfer, final boolean isExceptionForBalanceCheck) {
        this(isAccountTransfer, isRegularTransaction, isApplyWithdrawFee, isInterestTransfer, isExceptionForBalanceCheck, null);
    }

    /**
     * AB-243: {@code chargeableAmount} is the optional base for withdrawal-fee calculation when it differs from the
     * withdrawal amount. {@code null} = fees on the transaction amount as always; {@code 0} = no withdrawal charges at
     * all ({@link #isApplyWithdrawFee()} reports {@code false}); {@code > 0} = flat fees as configured, percentage fees
     * computed on this base ({@link #withdrawalFeeBase(BigDecimal)}).
     */
    public SavingsTransactionBooleanValues(final boolean isAccountTransfer, final boolean isRegularTransaction,
            final boolean isApplyWithdrawFee, final boolean isInterestTransfer, final boolean isExceptionForBalanceCheck,
            final BigDecimal chargeableAmount) {

        this.isAccountTransfer = isAccountTransfer;
        this.isRegularTransaction = isRegularTransaction;
        this.isApplyWithdrawFee = isApplyWithdrawFee;
        this.isInterestTransfer = isInterestTransfer;
        this.isExceptionForBalanceCheck = isExceptionForBalanceCheck;
        this.chargeableAmount = chargeableAmount;
    }

    public boolean isAccountTransfer() {
        return this.isAccountTransfer;
    }

    public boolean isRegularTransaction() {
        return this.isRegularTransaction;
    }

    public boolean isApplyWithdrawFee() {
        return this.isApplyWithdrawFee && (this.chargeableAmount == null || this.chargeableAmount.signum() > 0);
    }

    public boolean isInterestTransfer() {
        return this.isInterestTransfer;
    }

    public boolean isExceptionForBalanceCheck() {
        return this.isExceptionForBalanceCheck;
    }

    public BigDecimal chargeableAmount() {
        return this.chargeableAmount;
    }

    /**
     * The amount withdrawal fees are calculated on: the explicit chargeable amount when supplied, else the full
     * transaction amount.
     */
    public BigDecimal withdrawalFeeBase(final BigDecimal transactionAmount) {
        return this.chargeableAmount == null ? transactionAmount : this.chargeableAmount;
    }

}
