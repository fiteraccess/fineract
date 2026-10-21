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
package org.apache.fineract.portfolio.savings.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.junit.jupiter.api.Test;

class SavingsProductDataValidatorTest {

    private final SavingsProductDataValidator validator = new SavingsProductDataValidator(new FromJsonHelper(),
            mock(SavingsProductAccountingDataValidator.class));
    private final SavingsProduct product = mock(SavingsProduct.class);

    @Test
    void aNegativeMonthlyThresholdIsRejected() {
        PlatformApiDataValidationException error = catchThrowableOfType(PlatformApiDataValidationException.class,
                () -> validator.validateForUpdate("{\"monthlyWithdrawalThreshold\": -1}", product));

        assertThat(error.getErrors()).extracting(ApiParameterError::getParameterName).containsExactly("monthlyWithdrawalThreshold");
    }

    @Test
    void anUnknownQualifyingWithdrawalTypeIsRejected() {
        PlatformApiDataValidationException error = catchThrowableOfType(PlatformApiDataValidationException.class,
                () -> validator.validateForUpdate("{\"qualifyingWithdrawalTypes\": [\"AIRTIME\", \"CHEQUE_WITHDRAWAL\"]}", product));

        assertThat(error.getErrors()).extracting(ApiParameterError::getParameterName).containsExactly("qualifyingWithdrawalTypes");
    }

    @Test
    void qualifyingWithdrawalTypesMustBeAnArray() {
        PlatformApiDataValidationException error = catchThrowableOfType(PlatformApiDataValidationException.class,
                () -> validator.validateForUpdate("{\"qualifyingWithdrawalTypes\": \"AIRTIME\"}", product));

        assertThat(error.getErrors()).extracting(ApiParameterError::getParameterName).containsExactly("qualifyingWithdrawalTypes");
    }

    @Test
    void anExplicitNullCounterFlagIsRejected() {
        PlatformApiDataValidationException error = catchThrowableOfType(PlatformApiDataValidationException.class,
                () -> validator.validateForUpdate("{\"withdrawalCounterEnabled\": null}", product));

        assertThat(error.getErrors()).extracting(ApiParameterError::getParameterName).containsExactly("withdrawalCounterEnabled");
    }

    @Test
    void aValidRuleIsAccepted() {
        assertThatCode(() -> validator.validateForUpdate(
                "{\"withdrawalCounterEnabled\": false, \"monthlyWithdrawalThreshold\": 0, \"qualifyingWithdrawalTypes\": [\"DATA\", \"BILL_PAYMENT\"]}",
                product)).doesNotThrowAnyException();
    }

    @Test
    void theOptInCashCardAndAtmTypesAreAccepted() {
        assertThatCode(() -> validator.validateForUpdate(
                "{\"qualifyingWithdrawalTypes\": [\"CASH_WITHDRAWAL\", \"CARD_PURCHASE\", \"ATM_WITHDRAWAL\"]}", product))
                .doesNotThrowAnyException();
    }
}
