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
package org.apache.fineract.portfolio.loanproduct.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanproduct.data.CacheableLoanProductConfig;
import org.apache.fineract.portfolio.loanproduct.data.CacheableLoanProductVariableInstallmentConfig;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductConfigurableAttributes;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.loanproduct.exception.LoanProductNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for loading and caching loan product configuration data.
 *
 * <p>
 * This service provides a thin, cacheable DTO ({@link CacheableLoanProductConfig}) containing only the fields needed
 * during transaction processing and schedule generation. This avoids loading the full {@link LoanProduct} entity (with
 * its EAGER collections) on every loan transaction.
 * </p>
 *
 * <p>
 * Cache eviction is handled by {@link org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepositoryWrapper}
 * when loan products are modified.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheableLoanProductConfigService implements LoanProductConfigProvider {

    private final LoanProductRepository repository;

    /**
     * Get the loan product transaction configuration for the given product ID.
     *
     * <p>
     * This method is cached using the "loanProductConfig" cache. The cache key is the product ID. Tenant isolation is
     * handled by the {@link org.apache.fineract.infrastructure.core.config.cache.TenantAwareRedisCacheManager} which
     * prefixes all cache names with the tenant identifier.
     * </p>
     *
     * @param productId
     *            the loan product ID
     * @return the loan product transaction configuration
     * @throws LoanProductNotFoundException
     *             if the product is not found
     */
    @Override
    @Cacheable(value = "loanProductConfig", key = "':' + #productId")
    @Transactional(readOnly = true)
    public CacheableLoanProductConfig getProductConfig(Long productId) {
        log.info("CACHE MISS: loading CacheableLoanProductConfig for productId={} from DB (will attempt to cache result)", productId);

        LoanProduct p = repository.findById(productId).orElseThrow(() -> new LoanProductNotFoundException(productId));

        CacheableLoanProductConfig config = new CacheableLoanProductConfig();
        config.setId(p.getId());
        config.setName(p.getName());
        config.setShortName(p.getShortName());
        config.setAccountingRule(p.getAccountingRule().getValue());
        config.setMultiDisburseLoan(p.isMultiDisburseLoan());
        config.setInstallmentAmountInMultiplesOf(p.getLoanProductRelatedDetail().getInstallmentAmountInMultiplesOf());
        config.setMaxTrancheCount(p.getLoanProductTrancheDetails() != null ? p.getLoanProductTrancheDetails().getMaxTrancheCount() : null);
        config.setPrincipalThresholdForLastInstallment(p.getPrincipalThresholdForLastInstallment());
        config.setHasDelinquencyBucket(p.getDelinquencyBucket() != null);
        config.setRepaymentStartDateType(p.getRepaymentStartDateType());
        config.setPreCloseInterestCalculationStrategy(p.preCloseInterestCalculationStrategy());

        // Additional fields for service layer operations
        config.setIncludeInBorrowerCycle(p.isIncludeInBorrowerCycle());
        config.setDisallowExpectedDisbursements(p.isDisallowExpectedDisbursements());
        config.setSyncExpectedWithDisbursementDate(p.isSyncExpectedWithDisbursementDate());
        config.setCanDefineInstallmentAmount(p.isCanDefineInstallmentAmount());
        config.setCanUseForTopup(p.isCanUseForTopup());
        config.setHoldGuaranteeFunds(p.isHoldGuaranteeFunds());
        config.setArrearsBasedOnOriginalSchedule(p.isArrearsBasedOnOriginalSchedule());
        config.setInterestRecalculationEnabled(p.isInterestRecalculationEnabled());
        config.setAllowVariableInstallments(p.isAllowVariabeInstallments());
        config.setAllowApprovedDisbursedAmountsOverApplied(p.isAllowApprovedDisbursedAmountsOverApplied());
        config.setOverAppliedCalculationType(p.getOverAppliedCalculationType());
        config.setOverAppliedNumber(p.getOverAppliedNumber());

        config.setEnableAutoRepaymentForDownPayment(p.getLoanProductRelatedDetail().isEnableAutoRepaymentForDownPayment());

        config.setMinNumberOfRepayments(p.getMinNumberOfRepayments());
        config.setMaxNumberOfRepayments(p.getMaxNumberOfRepayments());
        config.setDueDaysForRepaymentEvent(p.getDueDaysForRepaymentEvent());
        config.setOverDueDaysForRepaymentEvent(p.getOverDueDaysForRepaymentEvent());

        config.setDelinquencyBucketId(p.getDelinquencyBucket() != null ? p.getDelinquencyBucket().getId() : null);

        // Floating rate configuration
        config.setFloatingRateId(p.getFloatingRateId());
        config.setFloatingRateDifferential(p.getFloatingRateDifferential());

        if (p.getVariableInstallmentConfig() != null) {
            CacheableLoanProductVariableInstallmentConfig clpvic = new CacheableLoanProductVariableInstallmentConfig();
            clpvic.setMaximumGap(p.getVariableInstallmentConfig().getMaximumGap());
            clpvic.setMinimumGap(p.getVariableInstallmentConfig().getMinimumGap());

            config.setLoanProductVariableInstallmentConfig(clpvic);
        }

        // Down payment percentage
        LoanProductRelatedDetail relatedDetail = p.getLoanProductRelatedDetail();
        config.setDisbursedAmountPercentageForDownPayment(relatedDetail.getDisbursedAmountPercentageForDownPayment());

        // Configurable attributes
        LoanProductConfigurableAttributes attrs = p.getLoanConfigurableAttributes();
        config.setHasConfigurableAttributes(attrs != null);
        if (attrs != null) {
            config.setConfigurableAmortization(attrs.getAmortizationBoolean());
            config.setConfigurableInterestMethod(attrs.getInterestMethodBoolean());
            config.setConfigurableTransactionProcessingStrategy(attrs.getTransactionProcessingStrategyBoolean());
            config.setConfigurableInterestCalcPeriod(attrs.getInterestCalcPeriodBoolean());
            config.setConfigurableArrearsTolerance(attrs.getArrearsToleranceBoolean());
            config.setConfigurableRepaymentEvery(attrs.getRepaymentEveryBoolean());
            config.setConfigurableGraceOnPrincipalAndInterestPayment(attrs.getGraceOnPrincipalAndInterestPaymentBoolean());
            config.setConfigurableGraceOnArrearsAging(attrs.getGraceOnArrearsAgingBoolean());
        }

        // Product related detail defaults (for updateProductRelatedDetails overrides)
        config.setDefaultAmortizationMethod(relatedDetail.getAmortizationMethod());
        config.setDefaultInArrearsTolerance(relatedDetail.getInArrearsTolerance().getAmount());
        config.setDefaultGraceOnArrearsAgeing(relatedDetail.getGraceOnArrearsAgeing());
        config.setDefaultInterestCalculationPeriodMethod(relatedDetail.getInterestCalculationPeriodMethod());
        config.setDefaultInterestMethod(relatedDetail.getInterestMethod());
        config.setDefaultGraceOnInterestPayment(relatedDetail.getGraceOnInterestPayment());
        config.setDefaultGraceOnPrincipalPayment(relatedDetail.getGraceOnPrincipalPayment());
        config.setDefaultRepayEvery(relatedDetail.getRepayEvery());

        // Overdue installment penalty charge
        if (p.getCharges() != null) {
            Optional<Charge> overdueCharge = p.getCharges().stream()
                    .filter(c -> ChargeTimeType.OVERDUE_INSTALLMENT.getValue().equals(c.getChargeTimeType()) && c.isLoanCharge())
                    .findFirst();
            if (overdueCharge.isPresent()) {
                config.setOverdueInstallmentPenaltyChargeId(overdueCharge.get().getId());
                config.setOverdueInstallmentPenaltyChargeAmount(overdueCharge.get().getAmount());
            }
        }

        return config;
    }
}
