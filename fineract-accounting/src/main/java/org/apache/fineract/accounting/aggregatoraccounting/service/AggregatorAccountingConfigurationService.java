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
package org.apache.fineract.accounting.aggregatoraccounting.service;

import static org.apache.fineract.accounting.aggregatoraccounting.api.AggregatorAccountingConfigurationApiConstants.ACTIVE;
import static org.apache.fineract.accounting.aggregatoraccounting.api.AggregatorAccountingConfigurationApiConstants.AGGREGATOR_PAYABLE_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.aggregatoraccounting.api.AggregatorAccountingConfigurationApiConstants.COMMISSION_INCOME_GL_ACCOUNT_ID;
import static org.apache.fineract.accounting.aggregatoraccounting.api.AggregatorAccountingConfigurationApiConstants.CONVENIENCE_FEE_INCOME_GL_ACCOUNT_ID;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.aggregatoraccounting.data.AggregatorAccountingConfigurationData;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationEntity;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationProvider;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationRepository;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorCodeNormalizer;
import org.apache.fineract.accounting.aggregatoraccounting.exception.AggregatorAccountingConfigurationInactiveException;
import org.apache.fineract.accounting.aggregatoraccounting.exception.AggregatorAccountingConfigurationNotFoundException;
import org.apache.fineract.accounting.aggregatoraccounting.serialization.AggregatorAccountingConfigurationValidator;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AggregatorAccountingConfigurationService implements AggregatorAccountingConfigurationProvider {

    private final PlatformSecurityContext context;
    private final AggregatorAccountingConfigurationRepository repository;
    private final GLAccountRepositoryWrapper glAccountRepository;
    private final AggregatorAccountingConfigurationValidator validator;

    @Override
    @Transactional(readOnly = true)
    public AggregatorAccountingConfigurationProvider.Configuration requireConfiguration(String aggregatorCode) {
        return requireActiveConfiguration(aggregatorCode).toConfiguration();
    }

    private AggregatorAccountingConfigurationEntity requireActiveConfiguration(String aggregatorCode) {
        String normalizedAggregatorCode = AggregatorCodeNormalizer.normalize(aggregatorCode);
        if (StringUtils.isBlank(normalizedAggregatorCode)) {
            throw new AggregatorAccountingConfigurationNotFoundException(normalizedAggregatorCode);
        }
        return repository.findByAggregatorCodeAndActiveTrue(normalizedAggregatorCode).orElseGet(() -> {
            if (repository.findByAggregatorCode(normalizedAggregatorCode).isPresent()) {
                throw new AggregatorAccountingConfigurationInactiveException(normalizedAggregatorCode);
            }
            throw new AggregatorAccountingConfigurationNotFoundException(normalizedAggregatorCode);
        });
    }

    @Transactional(readOnly = true)
    public AggregatorAccountingConfigurationData retrieve(String aggregatorCode) {
        String normalizedAggregatorCode = AggregatorCodeNormalizer.normalize(aggregatorCode);
        return repository.findByAggregatorCode(normalizedAggregatorCode).map(this::toData)
                .orElseThrow(() -> new AggregatorAccountingConfigurationNotFoundException(normalizedAggregatorCode));
    }

    @Transactional(readOnly = true)
    public List<AggregatorAccountingConfigurationData> retrieveAll() {
        return repository.findAllByOrderByAggregatorCodeAsc().stream().map(this::toData).toList();
    }

    @Transactional
    public CommandProcessingResult upsert(String aggregatorCode, JsonCommand command) {
        context.authenticatedUser();
        String normalizedAggregatorCode = AggregatorCodeNormalizer.normalize(aggregatorCode);
        validator.validateForUpsert(normalizedAggregatorCode, command);

        GLAccount aggregatorPayableGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(AGGREGATOR_PAYABLE_GL_ACCOUNT_ID),
                AGGREGATOR_PAYABLE_GL_ACCOUNT_ID);
        GLAccount commissionIncomeGlAccount = requireUsableGlAccount(command.longValueOfParameterNamed(COMMISSION_INCOME_GL_ACCOUNT_ID),
                COMMISSION_INCOME_GL_ACCOUNT_ID);
        GLAccount convenienceFeeIncomeGlAccount = requireUsableGlAccount(
                command.longValueOfParameterNamed(CONVENIENCE_FEE_INCOME_GL_ACCOUNT_ID), CONVENIENCE_FEE_INCOME_GL_ACCOUNT_ID);
        boolean active = command.booleanPrimitiveValueOfParameterNamed(ACTIVE);

        AggregatorAccountingConfigurationEntity entity = repository.findByAggregatorCode(normalizedAggregatorCode)
                .orElseGet(() -> AggregatorAccountingConfigurationEntity.create(normalizedAggregatorCode, aggregatorPayableGlAccount,
                        commissionIncomeGlAccount, convenienceFeeIncomeGlAccount, active));
        entity.replace(aggregatorPayableGlAccount, commissionIncomeGlAccount, convenienceFeeIncomeGlAccount, active);
        repository.saveAndFlush(entity);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(entity.getId())
                .withResourceIdAsString(normalizedAggregatorCode).build();
    }

    private GLAccount requireUsableGlAccount(Long glAccountId, String parameterName) {
        if (glAccountId == null) {
            return null;
        }
        GLAccount glAccount = glAccountRepository.findOneWithNotFoundDetection(glAccountId);
        if (glAccount.isDisabled() || !glAccount.isDetailAccount()) {
            throw new PlatformApiDataValidationException("error.msg.aggregator.accounting.configuration.gl.account.not.usable",
                    "The GL account must be an enabled detail account", parameterName, glAccountId);
        }
        return glAccount;
    }

    private AggregatorAccountingConfigurationData toData(AggregatorAccountingConfigurationEntity entity) {
        return new AggregatorAccountingConfigurationData(entity.getAggregatorCode(), idOf(entity.getAggregatorPayableGlAccount()),
                idOf(entity.getCommissionIncomeGlAccount()), idOf(entity.getConvenienceFeeIncomeGlAccount()), entity.isActive());
    }

    private Long idOf(GLAccount glAccount) {
        return glAccount == null ? null : glAccount.getId();
    }
}
