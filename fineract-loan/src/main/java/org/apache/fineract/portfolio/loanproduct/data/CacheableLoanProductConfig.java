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
package org.apache.fineract.portfolio.loanproduct.data;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.portfolio.loanproduct.domain.AmortizationMethod;
import org.apache.fineract.portfolio.loanproduct.domain.InterestCalculationPeriodMethod;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanPreCloseInterestCalculationStrategy;
import org.apache.fineract.portfolio.loanproduct.domain.RepaymentStartDateType;

/**
 * Thin, cacheable DTO containing only the loan product fields needed during transaction processing and schedule
 * generation. This avoids loading the full LoanProduct entity (with its EAGER collections) on every loan transaction.
 *
 * <p>
 * Fields included:
 * <ul>
 * <li>{@code id} — product identifier</li>
 * <li>{@code name} — product name</li>
 * <li>{@code shortName} — used for account number generation</li>
 * <li>{@code accountingRule} — determines accounting treatment (NONE, CASH, ACCRUAL_UPFRONT, ACCRUAL_PERIODIC)</li>
 * <li>{@code multiDisburseLoan} — whether the product supports multiple disbursements</li>
 * <li>{@code installmentAmountInMultiplesOf} — rounding for installment amounts</li>
 * <li>{@code maxTrancheCount} — maximum number of tranches for multi-disburse loans</li>
 * <li>{@code principalThresholdForLastInstallment} — threshold for last installment principal</li>
 * <li>{@code hasDelinquencyBucket} — whether the product has a delinquency bucket</li>
 * <li>{@code repaymentStartDateType} — determines repayment start date calculation</li>
 * <li>{@code preCloseInterestCalculationStrategy} — strategy for pre-closure interest calculation</li>
 * </ul>
 * </p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CacheableLoanProductConfig implements Serializable {

    private static final long serialVersionUID = 3L;

    private Long id;
    private String name;
    private String shortName;
    private Integer accountingRule;
    private boolean multiDisburseLoan;
    private Integer installmentAmountInMultiplesOf;
    private Integer maxTrancheCount;
    private BigDecimal principalThresholdForLastInstallment;
    private boolean hasDelinquencyBucket;

    // Fields for constructLoanApplicationTerms()
    private RepaymentStartDateType repaymentStartDateType;
    private LoanPreCloseInterestCalculationStrategy preCloseInterestCalculationStrategy;

    // Additional fields for service layer operations
    private boolean includeInBorrowerCycle;
    private boolean disallowExpectedDisbursements;
    private boolean syncExpectedWithDisbursementDate;
    private boolean canDefineInstallmentAmount;
    private boolean canUseForTopup;
    private boolean holdGuaranteeFunds;
    private boolean arrearsBasedOnOriginalSchedule;
    private boolean isInterestRecalculationEnabled;
    private boolean isAllowVariableInstallments;
    private boolean isEnableAutoRepaymentForDownPayment;
    private boolean allowApprovedDisbursedAmountsOverApplied;
    private String overAppliedCalculationType;
    private Integer overAppliedNumber;

    private Integer minNumberOfRepayments;
    private Integer maxNumberOfRepayments;

    private Integer dueDaysForRepaymentEvent;
    private Integer overDueDaysForRepaymentEvent;

    private Long delinquencyBucketId;

    CacheableLoanProductVariableInstallmentConfig loanProductVariableInstallmentConfig;

    // Down payment percentage
    private BigDecimal disbursedAmountPercentageForDownPayment;

    // Floating rates (cached from LoanProductFloatingRates)
    private Long floatingRateId;
    private BigDecimal floatingRateDifferential;

    // Configurable attributes (from LoanProductConfigurableAttributes)
    private boolean hasConfigurableAttributes;
    private Boolean configurableAmortization;
    private Boolean configurableInterestMethod;
    private Boolean configurableTransactionProcessingStrategy;
    private Boolean configurableInterestCalcPeriod;
    private Boolean configurableArrearsTolerance;
    private Boolean configurableRepaymentEvery;
    private Boolean configurableGraceOnPrincipalAndInterestPayment;
    private Boolean configurableGraceOnArrearsAging;

    // Product related detail defaults (for updateProductRelatedDetails overrides)
    private AmortizationMethod defaultAmortizationMethod;
    private BigDecimal defaultInArrearsTolerance;
    private Integer defaultGraceOnArrearsAgeing;
    private InterestCalculationPeriodMethod defaultInterestCalculationPeriodMethod;
    private InterestMethod defaultInterestMethod;
    private Integer defaultGraceOnInterestPayment;
    private Integer defaultGraceOnPrincipalPayment;
    private Integer defaultRepayEvery;

    // Overdue installment penalty charge (cached from product charges)
    private Long overdueInstallmentPenaltyChargeId;
    private BigDecimal overdueInstallmentPenaltyChargeAmount;

    /**
     * @return true if accounting is disabled for this product
     */
    public boolean isAccountingDisabled() {
        return AccountingRuleType.NONE.getValue().equals(this.accountingRule);
    }

    /**
     * @return true if cash-based accounting is enabled
     */
    public boolean isCashBasedAccountingEnabled() {
        return AccountingRuleType.CASH_BASED.getValue().equals(this.accountingRule);
    }

    /**
     * @return true if upfront accrual accounting is enabled
     */
    public boolean isUpfrontAccrualAccountingEnabled() {
        return AccountingRuleType.ACCRUAL_UPFRONT.getValue().equals(this.accountingRule);
    }

    /**
     * @return true if periodic accrual accounting is enabled
     */
    public boolean isPeriodicAccrualAccountingEnabled() {
        return AccountingRuleType.ACCRUAL_PERIODIC.getValue().equals(this.accountingRule);
    }

    /**
     * @return true if accounting is disabled, cash-based, or upfront accrual
     */
    public boolean isNoneOrCashOrUpfrontAccrualAccountingEnabled() {
        return isAccountingDisabled() || isCashBasedAccountingEnabled() || isUpfrontAccrualAccountingEnabled();
    }
}
