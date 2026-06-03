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
package org.apache.fineract.portfolio.client.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.infrastructure.configuration.data.GlobalConfigurationPropertyData;
import org.apache.fineract.infrastructure.configuration.service.ConfigurationReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientDataValidatorTest {

    public static final String DATE_FORMAT = "dd MMMM yyyy";

    @Mock
    private ConfigurationReadPlatformService configurationReadPlatformService;

    private ClientDataValidator underTest;

    @BeforeEach
    public void setUp() {
        // ClientDataValidator references DateUtils.getBusinessLocalDate() while building the date-of-birth validation
        // chain,
        // so we must populate ThreadLocalContextUtil with a business date before exercising validateForCreate(...).
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2024, 1, 1))));
        GlobalConfigurationPropertyData addressDisabled = new GlobalConfigurationPropertyData().setEnabled(false);
        given(configurationReadPlatformService.retrieveGlobalConfiguration(GlobalConfigurationConstants.ENABLE_ADDRESS))
                .willReturn(addressDisabled);
        underTest = new ClientDataValidator(new FromJsonHelper(), configurationReadPlatformService);
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void testValidateForCreate_ShouldNotThrow_WhenSavingsAccountNoSuppliedWithSavingsProductId() {
        String json = clientJson("""
                "savingsProductId": 1,
                "savingsAccountNo": "AB332-0001"
                """);
        assertThatCode(() -> underTest.validateForCreate(json)).doesNotThrowAnyException();
    }

    @Test
    public void testValidateForCreate_ShouldThrow_WhenSavingsAccountNoSuppliedWithoutSavingsProductId() {
        String json = clientJson("""
                "savingsAccountNo": "AB332-0001"
                """);
        PlatformApiDataValidationException result = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateForCreate(json));
        assertThat(result.getGlobalisationMessageCode()).isEqualTo("validation.msg.validation.errors.exist");
        assertThat(result.getErrors().getFirst().getUserMessageGlobalisationCode())
                .isEqualTo("validation.msg.client.savingsAccountNo.cannot.be.provided.without.savings.product");
    }

    @Test
    public void testValidateForCreate_ShouldThrow_WhenSavingsAccountNoIsBlank() {
        String json = clientJson("""
                "savingsProductId": 1,
                "savingsAccountNo": ""
                """);
        PlatformApiDataValidationException result = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateForCreate(json));
        assertThat(result.getGlobalisationMessageCode()).isEqualTo("validation.msg.validation.errors.exist");
        assertThat(result.getErrors().getFirst().getUserMessageGlobalisationCode())
                .isEqualTo("validation.msg.client.savingsAccountNo.cannot.be.blank");
    }

    private String clientJson(String extraFields) {
        return """
                {
                    "officeId": 1,
                    "legalFormId": 1,
                    "firstname": "Jane",
                    "lastname": "Doe",
                    "active": false,
                    "dateFormat": "%s",
                    "locale": "en",
                    %s
                }
                """.formatted(DATE_FORMAT, extraFields.trim().replaceAll(",\\s*$", ""));
    }
}
