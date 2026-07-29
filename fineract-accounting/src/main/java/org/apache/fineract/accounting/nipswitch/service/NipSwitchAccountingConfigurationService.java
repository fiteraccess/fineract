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
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.DIRECTION;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_FEE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_PAYABLE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.nipswitch.api.NipSwitchAccountingConfigurationApiConstants.SWITCH_RECEIVABLE_GL_ACCOUNT_ID;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.nipswitch.data.NipSwitchAccountingConfigurationData;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationEntity;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationRepository;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingDirection;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchIdNormalizer;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationDirectionException;
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
    public NipSwitchAccountingConfigurationProvider.OutboundConfiguration requireOutbound(String switchId) {
        NipSwitchAccountingConfigurationEntity entity = requireActiveConfiguration(switchId);
        if (!entity.getDirection().supportsOutbound()) {
            throw new NipSwitchAccountingConfigurationDirectionException(entity.getSwitchId(),
                    NipSwitchAccountingDirection.OUTBOUND.name());
        }
        return entity.toOutboundConfiguration();
    }

    @Override
    @Transactional(readOnly = true)
    public NipSwitchAccountingConfigurationProvider.InboundConfiguration requireInbound(String switchId) {
        NipSwitchAccountingConfigurationEntity entity = requireActiveConfiguration(switchId);
        if (!entity.getDirection().supportsInbound()) {
            throw new NipSwitchAccountingConfigurationDirectionException(entity.getSwitchId(), NipSwitchAccountingDirection.INBOUND.name());
        }
        return entity.toInboundConfiguration();
    }

    private NipSwitchAccountingConfigurationEntity requireActiveConfiguration(String switchId) {
        String normalizedSwitchId = NipSwitchIdNormalizer.normalize(switchId);
        if (StringUtils.isBlank(normalizedSwitchId)) {
            throw new NipSwitchAccountingConfigurationNotFoundException(normalizedSwitchId);
        }
        return repository.findBySwitchIdAndActiveTrue(normalizedSwitchId).orElseGet(() -> {
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

        NipSwitchAccountingDirection direction = NipSwitchAccountingDirection.valueOf(command.stringValueOfParameterNamed(DIRECTION));
        GLAccount switchPayableGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(SWITCH_PAYABLE_GL_ACCOUNT_ID),
                SWITCH_PAYABLE_GL_ACCOUNT_ID, false);
        GLAccount switchFeeGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(SWITCH_FEE_GL_ACCOUNT_ID),
                SWITCH_FEE_GL_ACCOUNT_ID, false);
        GLAccount commissionIncomeGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(COMMISSION_INCOME_GL_ACCOUNT_ID),
                COMMISSION_INCOME_GL_ACCOUNT_ID, false);
        GLAccount switchReceivableGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(SWITCH_RECEIVABLE_GL_ACCOUNT_ID),
                SWITCH_RECEIVABLE_GL_ACCOUNT_ID, true);
        boolean active = command.booleanPrimitiveValueOfParameterNamed(ACTIVE);

        NipSwitchAccountingConfigurationEntity entity = repository.findBySwitchId(normalizedSwitchId)
                .orElseGet(() -> NipSwitchAccountingConfigurationEntity.create(normalizedSwitchId, direction, switchPayableGlAccount,
                        switchFeeGlAccount, commissionIncomeGlAccount, switchReceivableGlAccount, active));
        entity.replace(direction, switchPayableGlAccount, switchFeeGlAccount, commissionIncomeGlAccount, switchReceivableGlAccount, active);
        repository.saveAndFlush(entity);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(entity.getId())
                .withResourceIdAsString(normalizedSwitchId).build();
    }

    private GLAccount requireUsableGlAccount(Long glAccountId, String parameterName, boolean mustBeAsset) {
        if (glAccountId == null) {
            return null;
        }
        GLAccount glAccount = glAccountRepository.findOneWithNotFoundDetection(glAccountId);
        if (glAccount.isDisabled() || !glAccount.isDetailAccount()
                || (mustBeAsset && !GLAccountType.ASSET.getValue().equals(glAccount.getType()))) {
            throw new PlatformApiDataValidationException("error.msg.nip.switch.accounting.configuration.gl.account.not.usable",
                    mustBeAsset ? "The Receivable GL account must be an enabled detail asset account"
                            : "The GL account must be an enabled detail account",
                    parameterName, glAccountId);
        }
        return glAccount;
    }

    private NipSwitchAccountingConfigurationData toData(NipSwitchAccountingConfigurationEntity entity) {
        return new NipSwitchAccountingConfigurationData(entity.getSwitchId(), entity.getDirection(),
                idOf(entity.getSwitchPayableGlAccount()), idOf(entity.getSwitchFeeGlAccount()), idOf(entity.getCommissionIncomeGlAccount()),
                idOf(entity.getSwitchReceivableGlAccount()), entity.isActive());
    }

    private Long idOf(GLAccount glAccount) {
        return glAccount == null ? null : glAccount.getId();
    }
}
