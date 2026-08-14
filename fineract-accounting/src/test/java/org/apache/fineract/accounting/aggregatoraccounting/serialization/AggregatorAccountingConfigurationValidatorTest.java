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
package org.apache.fineract.accounting.aggregatoraccounting.serialization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AggregatorAccountingConfigurationValidatorTest {

    private final AggregatorAccountingConfigurationValidator validator = new AggregatorAccountingConfigurationValidator(
            new FromJsonHelper());

    @Nested
    class ValidShapes {

        @Test
        void acceptsAConfigurationWithAConvenienceFeeAccount() {
            assertThatCode(() -> validator.validateForUpsert("CORALPAY", command("""
                    { "aggregatorPayableGlAccountId": 1, "commissionIncomeGlAccountId": 2,
                      "convenienceFeeIncomeGlAccountId": 3, "active": true }
                    """))).doesNotThrowAnyException();
        }

        @Test
        void acceptsAConfigurationWithoutAConvenienceFeeAccount() {
            assertThatCode(() -> validator.validateForUpsert("NOMIWORLD", command("""
                    { "aggregatorPayableGlAccountId": 1, "commissionIncomeGlAccountId": 2, "active": true }
                    """))).doesNotThrowAnyException();
        }
    }

    @Nested
    class InvalidShapes {

        @Test
        void rejectsMissingAggregatorCode() {
            assertThatThrownBy(() -> validator.validateForUpsert(null, command("""
                    { "aggregatorPayableGlAccountId": 1, "commissionIncomeGlAccountId": 2, "active": true }
                    """))).isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void rejectsMissingRequiredGlAccounts() {
            assertThatThrownBy(() -> validator.validateForUpsert("CORALPAY", command("""
                    { "commissionIncomeGlAccountId": 2, "active": true }
                    """))).isInstanceOf(PlatformApiDataValidationException.class);
            assertThatThrownBy(() -> validator.validateForUpsert("CORALPAY", command("""
                    { "aggregatorPayableGlAccountId": 1, "active": true }
                    """))).isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void rejectsANonPositiveConvenienceFeeAccountId() {
            assertThatThrownBy(() -> validator.validateForUpsert("CORALPAY", command("""
                    { "aggregatorPayableGlAccountId": 1, "commissionIncomeGlAccountId": 2,
                      "convenienceFeeIncomeGlAccountId": 0, "active": true }
                    """))).isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void rejectsMissingActive() {
            assertThatThrownBy(() -> validator.validateForUpsert("CORALPAY", command("""
                    { "aggregatorPayableGlAccountId": 1, "commissionIncomeGlAccountId": 2 }
                    """))).isInstanceOf(PlatformApiDataValidationException.class);
        }
    }

    private JsonCommand command(String json) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.json()).thenReturn(json);
        return command;
    }
}
