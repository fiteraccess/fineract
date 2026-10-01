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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.SavingsProductCategory;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.junit.jupiter.api.Test;

class SavingsProductCategoryRulesTest {

    private final SavingsProductRepository repository = mock(SavingsProductRepository.class);

    private static SavingsProduct product(Long id, SavingsProductCategory category, boolean periodicAccrual) {
        SavingsProduct product = mock(SavingsProduct.class);
        when(product.getId()).thenReturn(id);
        when(product.getProductCategory()).thenReturn(category);
        when(product.isPeriodicAccrualAccounting()).thenReturn(periodicAccrual);
        return product;
    }

    @Test
    void uncategorisedRequestIsNotChecked() {
        assertThatCode(() -> SavingsProductCategoryRules.assertCategoryAvailable(null, 1L, repository)).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }

    @Test
    void categoryRequiresPeriodicAccrual() {
        SavingsProduct product = product(34L, SavingsProductCategory.AUTOSAVE, false);

        assertThatThrownBy(() -> SavingsProductCategoryRules.assertPeriodicAccrual(product))
                .isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasFieldOrPropertyWithValue("globalisationMessageCode", "error.msg.savingsproduct.category.requires.periodic.accrual");
    }

    @Test
    void uncategorisedProductNeedsNoAccrual() {
        SavingsProduct product = product(67L, null, false);

        assertThatCode(() -> SavingsProductCategoryRules.assertPeriodicAccrual(product)).doesNotThrowAnyException();
    }

    @Test
    void secondGoalProductIsRejected() {
        SavingsProduct existing = product(335L, SavingsProductCategory.GOAL, true);
        when(repository.findByProductCategory(SavingsProductCategory.GOAL)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> SavingsProductCategoryRules.assertCategoryAvailable(SavingsProductCategory.GOAL, 1L, repository))
                .isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasFieldOrPropertyWithValue("globalisationMessageCode", "error.msg.savingsproduct.category.already.assigned");
    }

    @Test
    void newGoalProductIsRejectedWhenOneExists() {
        SavingsProduct existing = product(335L, SavingsProductCategory.GOAL, true);
        when(repository.findByProductCategory(SavingsProductCategory.GOAL)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> SavingsProductCategoryRules.assertCategoryAvailable(SavingsProductCategory.GOAL, null, repository))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void goalProductMayKeepItsOwnCategory() {
        SavingsProduct goal = product(335L, SavingsProductCategory.GOAL, true);
        when(repository.findByProductCategory(SavingsProductCategory.GOAL)).thenReturn(List.of(goal));

        assertThatCode(() -> SavingsProductCategoryRules.assertCategoryAvailable(SavingsProductCategory.GOAL, 335L, repository))
                .doesNotThrowAnyException();
    }

    @Test
    void manyDigitalProductsAreAllowed() {
        assertThatCode(() -> SavingsProductCategoryRules.assertCategoryAvailable(SavingsProductCategory.DIGITAL, 2L, repository))
                .doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }
}
