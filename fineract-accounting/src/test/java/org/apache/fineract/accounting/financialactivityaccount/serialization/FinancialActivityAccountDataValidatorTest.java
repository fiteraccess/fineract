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
package org.apache.fineract.accounting.financialactivityaccount.serialization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The four EOD bridge activities are accepted on create and update like every other mappable activity. */
class FinancialActivityAccountDataValidatorTest {

    private final FinancialActivityAccountDataValidator validator = new FinancialActivityAccountDataValidator(new FromJsonHelper());

    @ParameterizedTest
    @EnumSource(value = FinancialActivity.class, names = { "EOD_DEPOSIT_BRIDGE", "EOD_INCOME_BRIDGE", "EOD_TAX_BRIDGE",
            "EOD_INTEREST_BRIDGE" })
    void acceptsTheEodBridgeActivitiesOnCreateAndUpdate(final FinancialActivity activity) {
        final String json = "{\"financialActivityId\":" + activity.getValue() + ",\"glAccountId\":11}";

        assertThatCode(() -> validator.validateForCreate(json)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForUpdate(json)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = { 209, 999 })
    void stillRefusesAnUnknownActivity(final int activityId) {
        assertThatThrownBy(() -> validator.validateForCreate("{\"financialActivityId\":" + activityId + ",\"glAccountId\":11}"))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }
}
