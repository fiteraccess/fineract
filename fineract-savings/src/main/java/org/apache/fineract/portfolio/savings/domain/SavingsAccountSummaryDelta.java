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

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents the incremental changes (deltas) to apply to a {@link SavingsAccountSummary} when processing a single
 * transaction. All fields default to {@link BigDecimal#ZERO}, meaning no change. Positive values represent additions;
 * negative values represent subtractions (e.g., accountBalance can be negative for withdrawals).
 */
@Getter
@Setter
public final class SavingsAccountSummaryDelta {

    private BigDecimal totalDeposits = BigDecimal.ZERO;
    private BigDecimal totalWithdrawals = BigDecimal.ZERO;
    private BigDecimal totalInterestPosted = BigDecimal.ZERO;
    private BigDecimal totalWithdrawalFees = BigDecimal.ZERO;
    private BigDecimal totalAnnualFees = BigDecimal.ZERO;
    private BigDecimal totalFeeCharge = BigDecimal.ZERO;
    private BigDecimal totalPenaltyCharge = BigDecimal.ZERO;
    private BigDecimal totalFeeChargesWaived = BigDecimal.ZERO;
    private BigDecimal totalPenaltyChargesWaived = BigDecimal.ZERO;
    private BigDecimal totalOverdraftInterestDerived = BigDecimal.ZERO;
    private BigDecimal totalWithholdTax = BigDecimal.ZERO;
    private BigDecimal accountBalance = BigDecimal.ZERO;

    /**
     * Returns true if all delta fields are zero, meaning the transaction had no effect on the summary.
     */
    public boolean isZero() {
        return totalDeposits.signum() == 0 && totalWithdrawals.signum() == 0 && totalInterestPosted.signum() == 0
                && totalWithdrawalFees.signum() == 0 && totalAnnualFees.signum() == 0 && totalFeeCharge.signum() == 0
                && totalPenaltyCharge.signum() == 0 && totalFeeChargesWaived.signum() == 0 && totalPenaltyChargesWaived.signum() == 0
                && totalOverdraftInterestDerived.signum() == 0 && totalWithholdTax.signum() == 0 && accountBalance.signum() == 0;
    }
}
