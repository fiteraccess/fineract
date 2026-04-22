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
package org.apache.fineract.accounting.producttoaccountmapping.service;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.accounting.producttoaccountmapping.exception.ProductToGLAccountMappingNotFoundException;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cached resolver for GL Account IDs used during journal entry posting.
 *
 * Returns {@code Long} (GL Account ID) rather than JPA entities to avoid detached-entity issues with EhCache heap
 * storage. Callers should use {@code GLAccountRepository.getReferenceById(id)} to obtain a session-bound JPA proxy.
 *
 * Uses the {@code productGLMappings} cache which is evicted on loan/savings product create and update operations.
 */
@Service
@RequiredArgsConstructor
public class ProductToGLAccountResolver {

    private final ProductToGLAccountMappingRepository accountMappingRepository;
    private final FinancialActivityAccountRepositoryWrapper financialActivityAccountRepository;
    private final GLClosureRepository closureRepository;
    private final ChargeRepositoryWrapper chargeRepositoryWrapper;

    @Cacheable(value = "productGLMappings", key = "'resolve:' + #productId + ':' + #productType + ':' + #accountMappingTypeId + ':' + #paymentTypeId")
    public Long resolveGLAccountIdForProduct(final Long productId, final int productType, final int accountMappingTypeId,
            final Long paymentTypeId) {
        if (FinancialActivity.fromInt(accountMappingTypeId) != null) {
            return financialActivityAccountRepository.findByFinancialActivityTypeWithNotFoundDetection(accountMappingTypeId).getGlAccount()
                    .getId();
        }

        ProductToGLAccountMapping accountMapping = this.accountMappingRepository.findCoreProductToFinAccountMapping(productId, productType,
                accountMappingTypeId);

        if (paymentTypeId != null) {
            final ProductToGLAccountMapping paymentChannelSpecificAccountMapping = this.accountMappingRepository
                    .findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(productId, productType, accountMappingTypeId,
                            paymentTypeId);
            if (paymentChannelSpecificAccountMapping != null) {
                accountMapping = paymentChannelSpecificAccountMapping;
            }
        }

        if (accountMapping == null) {
            throw new ProductToGLAccountMappingNotFoundException(PortfolioProductType.fromInt(productType), productId,
                    String.valueOf(accountMappingTypeId));
        }
        return accountMapping.getGlAccount().getId();
    }

    @Cacheable(value = "productGLMappings", key = "'resolve:chargeOffReason:' + #productId + ':' + #productType + ':' + #chargeOffReasonId")
    public Long resolveGLAccountIdForChargeOffReason(final Long productId, final int productType, final Long chargeOffReasonId) {
        if (chargeOffReasonId == null) {
            return null;
        }
        final ProductToGLAccountMapping mapping = this.accountMappingRepository.findChargeOffReasonMapping(productId, productType,
                chargeOffReasonId);
        return mapping != null ? mapping.getGlAccount().getId() : null;
    }

    @Cacheable(value = "productGLMappings", key = "'resolve:charge:' + #productId + ':' + #productType + ':' + #accountMappingTypeId + ':' + #chargeId")
    public Long resolveGLAccountIdForCharge(final Long productId, final int productType, final int accountMappingTypeId,
            final Long chargeId) {
        ProductToGLAccountMapping accountMapping = this.accountMappingRepository.findCoreProductToFinAccountMapping(productId, productType,
                accountMappingTypeId);

        if (chargeId != null) {
            final ProductToGLAccountMapping chargeSpecificAccountMapping = this.accountMappingRepository
                    .findProductIdAndProductTypeAndFinancialAccountTypeAndChargeId(productId, productType, accountMappingTypeId, chargeId);
            if (chargeSpecificAccountMapping != null) {
                accountMapping = chargeSpecificAccountMapping;
            }
        }

        if (accountMapping == null) {
            throw new ProductToGLAccountMappingNotFoundException(PortfolioProductType.fromInt(productType), productId,
                    String.valueOf(accountMappingTypeId));
        }
        return accountMapping.getGlAccount().getId();
    }

    @Cacheable(value = "glClosures", key = "'closure:' + #officeId")
    public LocalDate resolveLatestClosingDateByBranch(final Long officeId) {
        GLClosure closure = closureRepository.getLatestGLClosureByBranch(officeId);
        return closure != null ? closure.getClosingDate() : null;
    }

    @Cacheable(value = "charges", key = "'chargeGL:' + #chargeId")
    public Long resolveGLAccountIdForChargeEntity(final Long chargeId) {
        GLAccount glAccount = chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId).getAccount();
        return glAccount != null ? glAccount.getId() : null;
    }
}
