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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.QualifyingWithdrawalType;
import org.apache.fineract.portfolio.savings.data.SavingsProductDataValidator;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsProductWithdrawalRule;
import org.apache.fineract.portfolio.savings.domain.SavingsProductWithdrawalRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AB-566: a new product's withdrawal rule starts this business month; a change starts next month. */
class SavingsProductWritePlatformServiceWithdrawalRuleTest {

    private static final Long PRODUCT_ID = 12L;
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    private final SavingsProductRepository productRepository = mock(SavingsProductRepository.class);
    private final SavingsProductAssembler assembler = mock(SavingsProductAssembler.class);
    private final ProductToGLAccountMappingWritePlatformService accountMappings = mock(ProductToGLAccountMappingWritePlatformService.class);
    private final SavingsProductWithdrawalRuleRepository ruleRepository = mock(SavingsProductWithdrawalRuleRepository.class);
    private final SavingsProductWritePlatformServiceJpaRepositoryImpl service = new SavingsProductWritePlatformServiceJpaRepositoryImpl(
            mock(PlatformSecurityContext.class), productRepository, mock(SavingsProductDataValidator.class), assembler, accountMappings,
            mock(FineractEntityAccessUtil.class), ruleRepository);
    private final SavingsProduct product = mock(SavingsProduct.class);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 10, 20))));
        when(product.getId()).thenReturn(PRODUCT_ID);
        when(product.update(any(JsonCommand.class))).thenReturn(new HashMap<>());
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(accountMappings.updateSavingsProductToGLAccountMapping(any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(new HashMap<>());
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void aNewProductsRuleTakesEffectThisMonth() {
        JsonCommand command = command("{\"monthlyWithdrawalThreshold\": 3}");
        when(assembler.assemble(command)).thenReturn(product);
        when(assembler.assembleWithdrawalRule(any(), any())).thenCallRealMethod();

        service.create(command);

        verify(ruleRepository).save(PRODUCT_ID, new SavingsProductWithdrawalRule(OCTOBER, true, 3, QualifyingWithdrawalType.DEFAULTS));
    }

    @Test
    void aChangedRuleTakesEffectNextMonth() {
        when(ruleRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(SavingsProductWithdrawalRule.defaults(OCTOBER)));

        CommandProcessingResult result = service.update(PRODUCT_ID,
                command("{\"withdrawalCounterEnabled\": false, \"qualifyingWithdrawalTypes\": [\"AIRTIME\"]}"));

        verify(ruleRepository).save(PRODUCT_ID, new SavingsProductWithdrawalRule(NOVEMBER, false, 4, List.of(QualifyingWithdrawalType.AIRTIME)));
        assertThat(result.getChanges()).containsEntry("withdrawalRuleEffectiveFrom", "2026-11-01");
    }

    @Test
    void anUpdateRepeatingTheScheduledTermsStoresNothing() {
        when(ruleRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(SavingsProductWithdrawalRule.defaults(OCTOBER)));

        service.update(PRODUCT_ID, command("{\"monthlyWithdrawalThreshold\": 4}"));

        verify(ruleRepository, never()).save(eq(PRODUCT_ID), any());
    }

    private static JsonCommand command(final String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), "SAVINGSPRODUCT", PRODUCT_ID, null, null, null,
                null, null, null, null, PRODUCT_ID, null, null, null, null);
    }
}
