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
package org.apache.fineract.accounting.switchglconfiguration.serialization;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.switchglconfiguration.api.SwitchGlConfigurationsJsonInputParams;
import org.apache.fineract.accounting.switchglconfiguration.domain.SwitchDirection;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class SwitchGlConfigurationDataValidator {

    private final Set<String> supportedParameters = SwitchGlConfigurationsJsonInputParams.getAllValues();

    private static final String SWITCH_CODE = SwitchGlConfigurationsJsonInputParams.SWITCH_CODE.getValue();
    private static final String DIRECTION = SwitchGlConfigurationsJsonInputParams.DIRECTION.getValue();
    private static final String PRINCIPAL_GL_ACCOUNT_ID = SwitchGlConfigurationsJsonInputParams.PRINCIPAL_GL_ACCOUNT_ID.getValue();
    private static final String SWITCH_FEE_GL_ACCOUNT_ID = SwitchGlConfigurationsJsonInputParams.SWITCH_FEE_GL_ACCOUNT_ID.getValue();
    private static final String BANK_COMMISSION_GL_ACCOUNT_ID = SwitchGlConfigurationsJsonInputParams.BANK_COMMISSION_GL_ACCOUNT_ID
            .getValue();
    private static final String ACTIVE = SwitchGlConfigurationsJsonInputParams.ACTIVE.getValue();

    private final FromJsonHelper fromApiJsonHelper;

    public void validateForCreate(final String json) {
        validateJSONAndCheckForUnsupportedParams(json);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = getDataValidator(dataValidationErrors);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final String switchCode = this.fromApiJsonHelper.extractStringNamed(SWITCH_CODE, element);
        baseDataValidator.reset().parameter(SWITCH_CODE).value(switchCode).notBlank().notExceedingLengthOf(20);

        final Integer direction = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(DIRECTION, element);
        baseDataValidator.reset().parameter(DIRECTION).value(direction).notNull().isOneOfTheseValues(SwitchDirection.OUTBOUND.getValue(),
                SwitchDirection.INBOUND.getValue());

        final Long principalGlAccountId = this.fromApiJsonHelper.extractLongNamed(PRINCIPAL_GL_ACCOUNT_ID, element);
        baseDataValidator.reset().parameter(PRINCIPAL_GL_ACCOUNT_ID).value(principalGlAccountId).notNull().integerGreaterThanZero();

        validateFeeAndCommissionForDirection(baseDataValidator, element, SwitchDirection.fromInt(direction));

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    public void validateForUpdate(final String json) {
        validateJSONAndCheckForUnsupportedParams(json);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = getDataValidator(dataValidationErrors);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        if (this.fromApiJsonHelper.parameterExists(SWITCH_CODE, element)) {
            final String switchCode = this.fromApiJsonHelper.extractStringNamed(SWITCH_CODE, element);
            baseDataValidator.reset().parameter(SWITCH_CODE).value(switchCode).ignoreIfNull().notBlank().notExceedingLengthOf(20);
        }

        Integer direction = null;
        if (this.fromApiJsonHelper.parameterExists(DIRECTION, element)) {
            direction = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(DIRECTION, element);
            baseDataValidator.reset().parameter(DIRECTION).value(direction).ignoreIfNull()
                    .isOneOfTheseValues(SwitchDirection.OUTBOUND.getValue(), SwitchDirection.INBOUND.getValue());
        }

        if (this.fromApiJsonHelper.parameterExists(PRINCIPAL_GL_ACCOUNT_ID, element)) {
            final Long principalGlAccountId = this.fromApiJsonHelper.extractLongNamed(PRINCIPAL_GL_ACCOUNT_ID, element);
            baseDataValidator.reset().parameter(PRINCIPAL_GL_ACCOUNT_ID).value(principalGlAccountId).ignoreIfNull()
                    .integerGreaterThanZero();
        }

        if (direction != null) {
            validateFeeAndCommissionForDirection(baseDataValidator, element, SwitchDirection.fromInt(direction));
        }

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    /**
     * Fee/commission GL accounts are required for OUTBOUND (fee/commission only ever apply outbound) and must be absent
     * for INBOUND (inbound NIP credits carry no fee - nothing to configure).
     */
    private void validateFeeAndCommissionForDirection(final DataValidatorBuilder baseDataValidator, final JsonElement element,
            final SwitchDirection direction) {
        final Long switchFeeGlAccountId = this.fromApiJsonHelper.extractLongNamed(SWITCH_FEE_GL_ACCOUNT_ID, element);
        final Long bankCommissionGlAccountId = this.fromApiJsonHelper.extractLongNamed(BANK_COMMISSION_GL_ACCOUNT_ID, element);

        if (direction != null && direction.isOutbound()) {
            baseDataValidator.reset().parameter(SWITCH_FEE_GL_ACCOUNT_ID).value(switchFeeGlAccountId).notNull().integerGreaterThanZero();
            baseDataValidator.reset().parameter(BANK_COMMISSION_GL_ACCOUNT_ID).value(bankCommissionGlAccountId).notNull()
                    .integerGreaterThanZero();
        } else if (direction != null && direction.isInbound()) {
            baseDataValidator.reset().parameter(SWITCH_FEE_GL_ACCOUNT_ID).value(switchFeeGlAccountId)
                    .mustBeBlankWhenParameterProvidedIs(DIRECTION, direction.name());
            baseDataValidator.reset().parameter(BANK_COMMISSION_GL_ACCOUNT_ID).value(bankCommissionGlAccountId)
                    .mustBeBlankWhenParameterProvidedIs(DIRECTION, direction.name());
        }
    }

    private DataValidatorBuilder getDataValidator(final List<ApiParameterError> dataValidationErrors) {
        return new DataValidatorBuilder(dataValidationErrors).resource("switchglconfiguration");
    }

    private void validateJSONAndCheckForUnsupportedParams(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, this.supportedParameters);
    }

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                    dataValidationErrors);
        }
    }
}
