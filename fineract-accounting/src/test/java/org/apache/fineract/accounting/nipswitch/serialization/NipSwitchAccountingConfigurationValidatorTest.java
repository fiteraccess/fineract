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
package org.apache.fineract.accounting.nipswitch.serialization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

class NipSwitchAccountingConfigurationValidatorTest {

    private final NipSwitchAccountingConfigurationValidator validator = new NipSwitchAccountingConfigurationValidator(new FromJsonHelper());

    @Test
    void acceptsCompleteConfiguration() {
        JsonCommand command = command("""
                {
                  "switchPayableGlAccountId": 1,
                  "switchFeeGlAccountId": 2,
                  "commissionIncomeGlAccountId": 3,
                  "active": true
                }
                """);

        assertThatCode(() -> validator.validateForUpsert("NIBSS", command)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankSwitchAndInvalidConfigurationValues() {
        JsonCommand command = command("""
                {
                  "switchPayableGlAccountId": 0,
                  "switchFeeGlAccountId": -2
                }
                """);

        assertThatThrownBy(() -> validator.validateForUpsert(" ", command)).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> {
                    PlatformApiDataValidationException validationException = (PlatformApiDataValidationException) exception;
                    org.assertj.core.api.Assertions.assertThat(validationException.getErrors()).hasSize(5);
                });
    }

    private JsonCommand command(String json) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.json()).thenReturn(json);
        return command;
    }
}
