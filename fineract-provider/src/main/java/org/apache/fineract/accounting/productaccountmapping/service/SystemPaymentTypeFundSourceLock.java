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
package org.apache.fineract.accounting.productaccountmapping.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingConstants.SavingProductAccountingParams;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.springframework.stereotype.Component;

/**
 * Keeps a savings product's fund source for a system-defined payment type fixed while
 * {@code lock-system-payment-type-fund-sources} is enabled. Synapse resolves the same mapping for the other core, and a
 * reversal resolves it again later, so a remap would split one transaction across two GLs.
 */
@Component
@RequiredArgsConstructor
public class SystemPaymentTypeFundSourceLock {

    private final ConfigurationDomainService configurationDomainService;
    private final ProductToGLAccountMappingRepository accountMappingRepository;
    private final FromJsonHelper fromApiJsonHelper;

    /**
     * Refuses a savings product update that would change or remove a locked mapping. Without the mapping array an
     * ordinary update leaves the mappings alone, but an accounting-rule change rebuilds them from that array.
     */
    public void assertUnchanged(final Long savingsProductId, final JsonElement element, final boolean accountingRuleChanged,
            final AccountingRuleType accountingRuleType) {
        if (!this.configurationDomainService.isSystemPaymentTypeFundSourceLockEnabled()) {
            return;
        }
        final List<ProductToGLAccountMapping> locked = findLocked(savingsProductId);
        if (locked.isEmpty()) {
            return;
        }
        final JsonArray requested = this.fromApiJsonHelper
                .extractJsonArrayNamed(SavingProductAccountingParams.PAYMENT_CHANNEL_FUND_SOURCE_MAPPING.getValue(), element);
        if (!accountingRuleChanged && requested == null) {
            return;
        }
        final Map<Long, Long> requestedGlAccounts = accountingRuleChanged && accountingRuleType == AccountingRuleType.NONE ? Map.of()
                : glAccountsByPaymentType(requested);
        for (final ProductToGLAccountMapping mapping : locked) {
            if (!Objects.equals(mapping.getGlAccount().getId(), requestedGlAccounts.get(mapping.getPaymentType().getId()))) {
                throw lockedMapping(savingsProductId, mapping);
            }
        }
    }

    private List<ProductToGLAccountMapping> findLocked(final Long savingsProductId) {
        return this.accountMappingRepository
                .findAllPaymentTypeToFundSourceMappings(savingsProductId, PortfolioProductType.SAVING.getValue()).stream()
                .filter(mapping -> Boolean.TRUE.equals(mapping.getPaymentType().getIsSystemDefined())).toList();
    }

    private static Map<Long, Long> glAccountsByPaymentType(final JsonArray requested) {
        final Map<Long, Long> glAccounts = new HashMap<>();
        if (requested == null) {
            return glAccounts;
        }
        for (final JsonElement row : requested) {
            glAccounts.put(row.getAsJsonObject().get(SavingProductAccountingParams.PAYMENT_TYPE.getValue()).getAsLong(),
                    row.getAsJsonObject().get(SavingProductAccountingParams.FUND_SOURCE.getValue()).getAsLong());
        }
        return glAccounts;
    }

    private static GeneralPlatformDomainRuleException lockedMapping(final Long savingsProductId, final ProductToGLAccountMapping mapping) {
        return new GeneralPlatformDomainRuleException("error.msg.product.fund.source.mapping.locked",
                "Savings product " + savingsProductId + " cannot change or remove the fund source of system-defined payment type "
                        + mapping.getPaymentType().getName()
                        + " while lock-system-payment-type-fund-sources is enabled; create a new system-defined payment type instead",
                savingsProductId, mapping.getPaymentType().getId());
    }
}
