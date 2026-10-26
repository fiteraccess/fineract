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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import java.util.List;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemPaymentTypeFundSourceLockTest {

    private static final long PRODUCT_ID = 232L;
    private static final long BRIDGE_PAYMENT_TYPE_ID = 9003L;
    private static final long CHANNEL_PAYMENT_TYPE_ID = 1L;
    private static final long BRIDGE_GL_ID = 6112L;
    private static final long OTHER_GL_ID = 6111L;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private ProductToGLAccountMappingRepository accountMappingRepository;

    private SystemPaymentTypeFundSourceLock lock;

    @BeforeEach
    void setUp() {
        lock = new SystemPaymentTypeFundSourceLock(configurationDomainService, accountMappingRepository, fromJsonHelper);
    }

    @Nested
    class WhenEnabled {

        @BeforeEach
        void setUp() {
            when(configurationDomainService.isSystemPaymentTypeFundSourceLockEnabled()).thenReturn(true);
            when(accountMappingRepository.findAllPaymentTypeToFundSourceMappings(PRODUCT_ID, PortfolioProductType.SAVING.getValue()))
                    .thenReturn(List.of(mapping(BRIDGE_PAYMENT_TYPE_ID, true, BRIDGE_GL_ID),
                            mapping(CHANNEL_PAYMENT_TYPE_ID, false, BRIDGE_GL_ID)));
        }

        @Test
        void refusesAChangedGl() {
            var body = json("{\"paymentChannelToFundSourceMappings\": [{\"paymentTypeId\": 9003, \"fundSourceAccountId\": 6111}]}");

            assertThatThrownBy(() -> lock.assertUnchanged(PRODUCT_ID, body, false, AccountingRuleType.CASH_BASED))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class)
                    .hasFieldOrPropertyWithValue("globalisationMessageCode", "error.msg.product.fund.source.mapping.locked");
        }

        @Test
        void refusesAMappingLeftOutOfTheRequest() {
            var body = json("{\"paymentChannelToFundSourceMappings\": [{\"paymentTypeId\": 1, \"fundSourceAccountId\": 6112}]}");

            assertThatThrownBy(() -> lock.assertUnchanged(PRODUCT_ID, body, false, AccountingRuleType.CASH_BASED))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class);
        }

        @Test
        void refusesAnEmptyMappingArray() {
            var body = json("{\"paymentChannelToFundSourceMappings\": []}");

            assertThatThrownBy(() -> lock.assertUnchanged(PRODUCT_ID, body, false, AccountingRuleType.CASH_BASED))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class);
        }

        @Test
        void allowsAnUnchangedMappingNextToOtherEdits() {
            var body = json("{\"paymentChannelToFundSourceMappings\": [{\"paymentTypeId\": 9003, \"fundSourceAccountId\": 6112},"
                    + " {\"paymentTypeId\": 1, \"fundSourceAccountId\": 6111}, {\"paymentTypeId\": 2, \"fundSourceAccountId\": 6111}]}");

            assertThatCode(() -> lock.assertUnchanged(PRODUCT_ID, body, false, AccountingRuleType.CASH_BASED)).doesNotThrowAnyException();
        }

        @Test
        void allowsAnUpdateThatDoesNotTouchTheMappings() {
            var body = json("{\"name\": \"Regular savings\"}");

            assertThatCode(() -> lock.assertUnchanged(PRODUCT_ID, body, false, AccountingRuleType.CASH_BASED)).doesNotThrowAnyException();
        }

        @Test
        void refusesAnAccountingRuleChangeThatDropsTheMappings() {
            var body = json("{\"accountingRule\": 3}");

            assertThatThrownBy(() -> lock.assertUnchanged(PRODUCT_ID, body, true, AccountingRuleType.ACCRUAL_PERIODIC))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class);
        }

        @Test
        void refusesAnAccountingRuleChangeToNoneEvenWhenTheMappingIsResent() {
            var body = json("{\"accountingRule\": 1,"
                    + " \"paymentChannelToFundSourceMappings\": [{\"paymentTypeId\": 9003, \"fundSourceAccountId\": 6112}]}");

            assertThatThrownBy(() -> lock.assertUnchanged(PRODUCT_ID, body, true, AccountingRuleType.NONE))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class);
        }

        @Test
        void allowsAnAccountingRuleChangeThatKeepsTheMapping() {
            var body = json("{\"accountingRule\": 3,"
                    + " \"paymentChannelToFundSourceMappings\": [{\"paymentTypeId\": 9003, \"fundSourceAccountId\": 6112}]}");

            assertThatCode(() -> lock.assertUnchanged(PRODUCT_ID, body, true, AccountingRuleType.ACCRUAL_PERIODIC))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void ignoresMappingsOfUserDefinedPaymentTypes() {
        when(configurationDomainService.isSystemPaymentTypeFundSourceLockEnabled()).thenReturn(true);
        when(accountMappingRepository.findAllPaymentTypeToFundSourceMappings(PRODUCT_ID, PortfolioProductType.SAVING.getValue()))
                .thenReturn(List.of(mapping(CHANNEL_PAYMENT_TYPE_ID, false, BRIDGE_GL_ID)));
        var body = json("{\"paymentChannelToFundSourceMappings\": []}");

        assertThatCode(() -> lock.assertUnchanged(PRODUCT_ID, body, false, AccountingRuleType.CASH_BASED)).doesNotThrowAnyException();
    }

    @Test
    void allowsEveryEditWhenDisabled() {
        when(configurationDomainService.isSystemPaymentTypeFundSourceLockEnabled()).thenReturn(false);
        var body = json("{\"paymentChannelToFundSourceMappings\": []}");

        assertThatCode(() -> lock.assertUnchanged(PRODUCT_ID, body, true, AccountingRuleType.NONE)).doesNotThrowAnyException();
        verifyNoInteractions(accountMappingRepository);
    }

    private JsonElement json(String body) {
        return fromJsonHelper.parse(body);
    }

    private static ProductToGLAccountMapping mapping(long paymentTypeId, boolean systemDefined, long glAccountId) {
        var paymentType = new PaymentType();
        paymentType.setId(paymentTypeId);
        paymentType.setName("payment type " + paymentTypeId);
        paymentType.setIsSystemDefined(systemDefined);
        var glAccount = new GLAccount();
        glAccount.setId(glAccountId);
        return new ProductToGLAccountMapping().setPaymentType(paymentType).setGlAccount(glAccount).setProductId(PRODUCT_ID)
                .setProductType(PortfolioProductType.SAVING.getValue()).setFinancialAccountType(1);
    }
}
