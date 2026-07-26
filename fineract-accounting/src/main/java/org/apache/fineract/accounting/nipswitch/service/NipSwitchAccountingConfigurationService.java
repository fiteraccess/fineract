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
package org.apache.fineract.accounting.nipswitch.service;

import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.ACTIVE;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.COMMISSION_INCOME_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_FEE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_PAYABLE_GL_ACCOUNT_ID;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.nipswitch.data.NipSwitchAccountingConfigurationData;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationEntity;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationRepository;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchIdNormalizer;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationInactiveException;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationNotFoundException;
import org.apache.fineract.accounting.nipswitch.serialization.NipSwitchAccountingConfigurationValidator;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NipSwitchAccountingConfigurationService implements NipSwitchAccountingConfigurationProvider {

    private final PlatformSecurityContext context;
    private final NipSwitchAccountingConfigurationRepository repository;
    private final GLAccountRepositoryWrapper glAccountRepository;
    private final NipSwitchAccountingConfigurationValidator validator;

    @Override
    @Transactional(readOnly = true)
    public NipSwitchAccountingConfigurationProvider.Configuration require(String switchId) {
        String normalizedSwitchId = NipSwitchIdNormalizer.normalize(switchId);
        if (StringUtils.isBlank(normalizedSwitchId)) {
            throw new NipSwitchAccountingConfigurationNotFoundException(normalizedSwitchId);
        }
        return repository.findBySwitchIdAndActiveTrue(normalizedSwitchId).map(NipSwitchAccountingConfigurationEntity::toConfiguration)
                .orElseGet(() -> {
                    if (repository.findBySwitchId(normalizedSwitchId).isPresent()) {
                        throw new NipSwitchAccountingConfigurationInactiveException(normalizedSwitchId);
                    }
                    throw new NipSwitchAccountingConfigurationNotFoundException(normalizedSwitchId);
                });
    }

    @Transactional(readOnly = true)
    public NipSwitchAccountingConfigurationData retrieve(String switchId) {
        String normalizedSwitchId = NipSwitchIdNormalizer.normalize(switchId);
        return repository.findBySwitchId(normalizedSwitchId).map(this::toData)
                .orElseThrow(() -> new NipSwitchAccountingConfigurationNotFoundException(normalizedSwitchId));
    }

    @Transactional(readOnly = true)
    public List<NipSwitchAccountingConfigurationData> retrieveAll() {
        return repository.findAllByOrderBySwitchIdAsc().stream().map(this::toData).toList();
    }

    @Transactional
    public CommandProcessingResult upsert(String switchId, JsonCommand command) {
        context.authenticatedUser();
        String normalizedSwitchId = NipSwitchIdNormalizer.normalize(switchId);
        validator.validateForUpsert(normalizedSwitchId, command);

        GLAccount switchPayableGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(SWITCH_PAYABLE_GL_ACCOUNT_ID),
                SWITCH_PAYABLE_GL_ACCOUNT_ID);
        GLAccount switchFeeGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(SWITCH_FEE_GL_ACCOUNT_ID),
                SWITCH_FEE_GL_ACCOUNT_ID);
        GLAccount commissionIncomeGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(COMMISSION_INCOME_GL_ACCOUNT_ID),
                COMMISSION_INCOME_GL_ACCOUNT_ID);
        boolean active = command.booleanPrimitiveValueOfParameterNamed(ACTIVE);

        NipSwitchAccountingConfigurationEntity entity = repository.findBySwitchId(normalizedSwitchId)
                .orElseGet(() -> NipSwitchAccountingConfigurationEntity.create(normalizedSwitchId, switchPayableGlAccount,
                        switchFeeGlAccount, commissionIncomeGlAccount, active));
        entity.replace(switchPayableGlAccount, switchFeeGlAccount, commissionIncomeGlAccount, active);
        repository.saveAndFlush(entity);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(entity.getId())
                .withResourceIdAsString(normalizedSwitchId).build();
    }

    private GLAccount requireUsableGlAccount(Long glAccountId, String parameterName) {
        GLAccount glAccount = glAccountRepository.findOneWithNotFoundDetection(glAccountId);
        if (glAccount.isDisabled() || !glAccount.isDetailAccount()) {
            throw new PlatformApiDataValidationException("error.msg.nip.switch.accounting.configuration.gl.account.not.usable",
                    "The GL account must be an enabled detail account", parameterName, glAccountId);
        }
        return glAccount;
    }

    private NipSwitchAccountingConfigurationData toData(NipSwitchAccountingConfigurationEntity entity) {
        return new NipSwitchAccountingConfigurationData(entity.getSwitchId(), entity.getSwitchPayableGlAccount().getId(),
                entity.getSwitchFeeGlAccount().getId(), entity.getCommissionIncomeGlAccount().getId(), entity.isActive());
    }
}
