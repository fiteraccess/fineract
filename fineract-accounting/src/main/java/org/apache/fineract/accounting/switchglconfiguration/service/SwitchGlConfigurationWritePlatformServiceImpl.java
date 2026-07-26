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
package org.apache.fineract.accounting.switchglconfiguration.service;

import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.switchglconfiguration.api.SwitchGlConfigurationsJsonInputParams;
import org.apache.fineract.accounting.switchglconfiguration.domain.SwitchDirection;
import org.apache.fineract.accounting.switchglconfiguration.domain.SwitchGlConfiguration;
import org.apache.fineract.accounting.switchglconfiguration.domain.SwitchGlConfigurationRepositoryWrapper;
import org.apache.fineract.accounting.switchglconfiguration.exception.DuplicateSwitchGlConfigurationFoundException;
import org.apache.fineract.accounting.switchglconfiguration.exception.SwitchGlConfigurationInvalidException;
import org.apache.fineract.accounting.switchglconfiguration.serialization.SwitchGlConfigurationDataValidator;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SwitchGlConfigurationWritePlatformServiceImpl implements SwitchGlConfigurationWritePlatformService {

    private final SwitchGlConfigurationRepositoryWrapper switchGlConfigurationRepository;
    private final SwitchGlConfigurationDataValidator fromApiJsonDeserializer;
    private final GLAccountRepositoryWrapper glAccountRepositoryWrapper;

    @Override
    public CommandProcessingResult createSwitchGlConfiguration(final JsonCommand command) {
        try {
            this.fromApiJsonDeserializer.validateForCreate(command.json());

            final String switchCode = command.stringValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.SWITCH_CODE.getValue());
            final Integer directionValue = command
                    .integerValueSansLocaleOfParameterNamed(SwitchGlConfigurationsJsonInputParams.DIRECTION.getValue());
            final SwitchDirection direction = SwitchDirection.fromInt(directionValue);

            final Long principalGlAccountId = command
                    .longValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.PRINCIPAL_GL_ACCOUNT_ID.getValue());
            final GLAccount principalGlAccount = this.glAccountRepositoryWrapper.findOneWithNotFoundDetection(principalGlAccountId);

            GLAccount switchFeeGlAccount = null;
            GLAccount bankCommissionGlAccount = null;
            if (direction.isOutbound()) {
                final Long switchFeeGlAccountId = command
                        .longValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.SWITCH_FEE_GL_ACCOUNT_ID.getValue());
                final Long bankCommissionGlAccountId = command
                        .longValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.BANK_COMMISSION_GL_ACCOUNT_ID.getValue());
                switchFeeGlAccount = this.glAccountRepositoryWrapper.findOneWithNotFoundDetection(switchFeeGlAccountId);
                bankCommissionGlAccount = this.glAccountRepositoryWrapper.findOneWithNotFoundDetection(bankCommissionGlAccountId);
            }

            final boolean active = command.parameterExists(SwitchGlConfigurationsJsonInputParams.ACTIVE.getValue())
                    ? command.booleanPrimitiveValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.ACTIVE.getValue())
                    : true;

            final SwitchGlConfiguration switchGlConfiguration = SwitchGlConfiguration.createNew(switchCode, direction, principalGlAccount,
                    switchFeeGlAccount, bankCommissionGlAccount, active);

            this.switchGlConfigurationRepository.saveAndFlush(switchGlConfiguration);

            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(switchGlConfiguration.getId()) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(command, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException ee) {
            final Throwable throwable = ExceptionUtils.getRootCause(ee.getCause());
            handleDataIntegrityIssues(command, throwable, ee);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult updateSwitchGlConfiguration(final Long id, final JsonCommand command) {
        try {
            this.fromApiJsonDeserializer.validateForUpdate(command.json());

            final SwitchGlConfiguration switchGlConfiguration = this.switchGlConfigurationRepository.findOneWithNotFoundDetection(id);

            SwitchDirection effectiveDirection = switchGlConfiguration.getDirection();

            if (command.parameterExists(SwitchGlConfigurationsJsonInputParams.SWITCH_CODE.getValue())) {
                switchGlConfiguration.updateSwitchCode(
                        command.stringValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.SWITCH_CODE.getValue()));
            }

            if (command.parameterExists(SwitchGlConfigurationsJsonInputParams.DIRECTION.getValue())) {
                effectiveDirection = SwitchDirection.fromInt(
                        command.integerValueSansLocaleOfParameterNamed(SwitchGlConfigurationsJsonInputParams.DIRECTION.getValue()));
                switchGlConfiguration.updateDirection(effectiveDirection);
            }

            if (command.parameterExists(SwitchGlConfigurationsJsonInputParams.PRINCIPAL_GL_ACCOUNT_ID.getValue())) {
                final Long principalGlAccountId = command
                        .longValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.PRINCIPAL_GL_ACCOUNT_ID.getValue());
                switchGlConfiguration
                        .updatePrincipalGlAccount(this.glAccountRepositoryWrapper.findOneWithNotFoundDetection(principalGlAccountId));
            }

            if (effectiveDirection.isOutbound()) {
                if (command.parameterExists(SwitchGlConfigurationsJsonInputParams.SWITCH_FEE_GL_ACCOUNT_ID.getValue())) {
                    final Long switchFeeGlAccountId = command
                            .longValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.SWITCH_FEE_GL_ACCOUNT_ID.getValue());
                    switchGlConfiguration
                            .updateSwitchFeeGlAccount(this.glAccountRepositoryWrapper.findOneWithNotFoundDetection(switchFeeGlAccountId));
                }
                if (command.parameterExists(SwitchGlConfigurationsJsonInputParams.BANK_COMMISSION_GL_ACCOUNT_ID.getValue())) {
                    final Long bankCommissionGlAccountId = command
                            .longValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.BANK_COMMISSION_GL_ACCOUNT_ID.getValue());
                    switchGlConfiguration.updateBankCommissionGlAccount(
                            this.glAccountRepositoryWrapper.findOneWithNotFoundDetection(bankCommissionGlAccountId));
                }
                if (switchGlConfiguration.getSwitchFeeGlAccount() == null || switchGlConfiguration.getBankCommissionGlAccount() == null) {
                    throw SwitchGlConfigurationInvalidException.feeCommissionRequiredForOutbound();
                }
            } else {
                if (switchGlConfiguration.getSwitchFeeGlAccount() != null || switchGlConfiguration.getBankCommissionGlAccount() != null) {
                    switchGlConfiguration.updateSwitchFeeGlAccount(null);
                    switchGlConfiguration.updateBankCommissionGlAccount(null);
                }
            }

            if (command.parameterExists(SwitchGlConfigurationsJsonInputParams.ACTIVE.getValue())) {
                switchGlConfiguration.updateActive(
                        command.booleanPrimitiveValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.ACTIVE.getValue()));
            }

            this.switchGlConfigurationRepository.saveAndFlush(switchGlConfiguration);

            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(id) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(command, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException ee) {
            final Throwable throwable = ExceptionUtils.getRootCause(ee.getCause());
            handleDataIntegrityIssues(command, throwable, ee);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult deleteSwitchGlConfiguration(final Long id, final JsonCommand command) {
        final SwitchGlConfiguration switchGlConfiguration = this.switchGlConfigurationRepository.findOneWithNotFoundDetection(id);
        this.switchGlConfigurationRepository.delete(switchGlConfiguration);
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(id) //
                .build();
    }

    private void handleDataIntegrityIssues(final JsonCommand command, final Throwable realCause, final Exception dve) {
        if (realCause.getMessage() != null && realCause.getMessage().contains("uq_switch_code_direction")) {
            final String switchCode = command.stringValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.SWITCH_CODE.getValue());
            final String direction = command.stringValueOfParameterNamed(SwitchGlConfigurationsJsonInputParams.DIRECTION.getValue());
            throw new DuplicateSwitchGlConfigurationFoundException(switchCode, direction);
        }

        log.error("Error occurred.", dve);
        throw ErrorHandler.getMappable(dve, "error.msg.switchglconfiguration.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource Switch GL Configuration: " + realCause.getMessage());
    }
}
