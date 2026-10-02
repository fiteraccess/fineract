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
package org.apache.fineract.portfolio.savings.service;

import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.SavingsProductCategory;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;

/**
 * Rules for a savings product's category. Applied only when the category is set or changed, so editing a categorised
 * product that is still on legacy accounting keeps working.
 */
final class SavingsProductCategoryRules {

    private SavingsProductCategoryRules() {}

    /**
     * Run before the product is changed: the query would otherwise flush the pending change, and the unique index would
     * reject it with an unspecific data-integrity error instead of this rule's message.
     */
    static void assertCategoryAvailable(final SavingsProductCategory category, final Long productId,
            final SavingsProductRepository repository) {
        if (category == null || !category.isSingleProduct()) {
            return;
        }
        final boolean takenByAnother = repository.findByProductCategory(category).stream()
                .anyMatch(other -> !other.getId().equals(productId));
        if (takenByAnother) {
            throw new GeneralPlatformDomainRuleException("error.msg.savingsproduct.category.already.assigned",
                    "Another savings product is already the " + category + " product", category);
        }
    }

    /** Run after the change is applied, so it sees the accounting rule the same request may also be setting. */
    static void assertPeriodicAccrual(final SavingsProduct product) {
        final SavingsProductCategory category = product.getProductCategory();
        if (category != null && !product.isPeriodicAccrualAccounting()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savingsproduct.category.requires.periodic.accrual",
                    "A " + category + " savings product must use periodic accrual accounting", category);
        }
    }
}
