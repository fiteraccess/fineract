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

import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.ACTIVE;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.COMMISSION_INCOME_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.DIRECTION;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_FEE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_PAYABLE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_RECEIVABLE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.UPSERT_PARAMETERS;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingDirection;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NipSwitchAccountingConfigurationValidator {

    private final FromJsonHelper fromApiJsonHelper;

    public void validateForUpsert(String switchId, JsonCommand command) {
        if (StringUtils.isBlank(command.json())) {
            throw new InvalidJsonException();
        }

        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        fromApiJsonHelper.checkForUnsupportedParameters(mapType, command.json(), UPSERT_PARAMETERS);
        JsonElement element = fromApiJsonHelper.parse(command.json());

        String directionValue = fromApiJsonHelper.extractStringNamed(DIRECTION, element);
        Long switchPayableGlAccountId = fromApiJsonHelper.extractLongNamed(SWITCH_PAYABLE_GL_ACCOUNT_ID, element);
        Long switchFeeGlAccountId = fromApiJsonHelper.extractLongNamed(SWITCH_FEE_GL_ACCOUNT_ID, element);
        Long commissionIncomeGlAccountId = fromApiJsonHelper.extractLongNamed(COMMISSION_INCOME_GL_ACCOUNT_ID, element);
        Long switchReceivableGlAccountId = fromApiJsonHelper.extractLongNamed(SWITCH_RECEIVABLE_GL_ACCOUNT_ID, element);
        Boolean active = fromApiJsonHelper.extractBooleanNamed(ACTIVE, element);

        List<ApiParameterError> errors = new ArrayList<>();
        DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("nipSwitchAccountingConfiguration");
        validator.reset().parameter("switchId").value(switchId).notBlank().notExceedingLengthOf(64);
        validator.reset().parameter(DIRECTION).value(directionValue).notBlank();
        validator.reset().parameter(ACTIVE).value(active).notNull();

        NipSwitchAccountingDirection direction = parseDirection(directionValue, validator);
        validateDirectionShape(direction, switchPayableGlAccountId, switchFeeGlAccountId, commissionIncomeGlAccountId,
                switchReceivableGlAccountId, validator);

        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }

    private NipSwitchAccountingDirection parseDirection(String directionValue, DataValidatorBuilder validator) {
        if (StringUtils.isBlank(directionValue)) {
            return null;
        }
        try {
            return NipSwitchAccountingDirection.valueOf(directionValue);
        } catch (IllegalArgumentException exception) {
            validator.reset().parameter(DIRECTION).value(directionValue).failWithCode("must.be.outbound.inbound.or.both");
            return null;
        }
    }

    private void validateDirectionShape(NipSwitchAccountingDirection direction, Long switchPayableGlAccountId, Long switchFeeGlAccountId,
            Long commissionIncomeGlAccountId, Long switchReceivableGlAccountId, DataValidatorBuilder validator) {
        boolean outbound = direction != null && direction.supportsOutbound();
        boolean inbound = direction != null && direction.supportsInbound();
        validateGlAccount(SWITCH_PAYABLE_GL_ACCOUNT_ID, switchPayableGlAccountId, outbound, validator);
        validateGlAccount(SWITCH_FEE_GL_ACCOUNT_ID, switchFeeGlAccountId, outbound, validator);
        validateGlAccount(COMMISSION_INCOME_GL_ACCOUNT_ID, commissionIncomeGlAccountId, outbound, validator);
        validateGlAccount(SWITCH_RECEIVABLE_GL_ACCOUNT_ID, switchReceivableGlAccountId, inbound, validator);
    }

    private void validateGlAccount(String parameterName, Long accountId, boolean required, DataValidatorBuilder validator) {
        DataValidatorBuilder accountValidator = validator.reset().parameter(parameterName).value(accountId);
        if (required) {
            accountValidator.notNull().longGreaterThanZero();
        } else if (accountId != null) {
            accountValidator.failWithCode("not.allowed.for.direction");
        }
    }
}
