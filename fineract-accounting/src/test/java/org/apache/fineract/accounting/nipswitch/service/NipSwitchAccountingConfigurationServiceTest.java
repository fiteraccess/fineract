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

import static org.apache.fineract.accounting.glaccount.domain.GLAccountUsage.DETAIL;
import static org.apache.fineract.accounting.glaccount.domain.GLAccountUsage.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.nipswitch.data.NipSwitchAccountingConfigurationData;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationEntity;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationRepository;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationInactiveException;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationNotFoundException;
import org.apache.fineract.accounting.nipswitch.serialization.NipSwitchAccountingConfigurationValidator;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NipSwitchAccountingConfigurationServiceTest {

    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final NipSwitchAccountingConfigurationRepository repository = mock(NipSwitchAccountingConfigurationRepository.class);
    private final GLAccountRepositoryWrapper glAccountRepository = mock(GLAccountRepositoryWrapper.class);
    private final NipSwitchAccountingConfigurationValidator validator = mock(NipSwitchAccountingConfigurationValidator.class);
    private NipSwitchAccountingConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new NipSwitchAccountingConfigurationService(context, repository, glAccountRepository, validator);
    }

    @Test
    void requiresActiveConfigurationUsingNormalizedSwitchId() {
        NipSwitchAccountingConfigurationEntity entity = configuration("NIBSS", true, detailAccount(1), detailAccount(2), detailAccount(3));
        when(repository.findBySwitchIdAndActiveTrue("NIBSS")).thenReturn(Optional.of(entity));

        NipSwitchAccountingConfigurationProvider.Configuration result = service.require(" nibss ");

        assertThat(result).isEqualTo(new NipSwitchAccountingConfigurationProvider.Configuration("NIBSS", 1L, 2L, 3L));
    }

    @Test
    void distinguishesInactiveFromMissingConfiguration() {
        NipSwitchAccountingConfigurationEntity inactive = configuration("NIBSS", false, detailAccount(1), detailAccount(2),
                detailAccount(3));
        when(repository.findBySwitchIdAndActiveTrue("NIBSS")).thenReturn(Optional.empty());
        when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.require("nibss")).isInstanceOf(NipSwitchAccountingConfigurationInactiveException.class);

        when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.require("nibss")).isInstanceOf(NipSwitchAccountingConfigurationNotFoundException.class);
    }

    @Test
    void retrievesOneAndListsConfigurationsInRepositoryOrder() {
        NipSwitchAccountingConfigurationEntity first = configuration("A_SWITCH", true, detailAccount(1), detailAccount(2),
                detailAccount(3));
        NipSwitchAccountingConfigurationEntity second = configuration("B_SWITCH", false, detailAccount(4), detailAccount(5),
                detailAccount(6));
        when(repository.findBySwitchId("A_SWITCH")).thenReturn(Optional.of(first));
        when(repository.findAllByOrderBySwitchIdAsc()).thenReturn(List.of(first, second));

        assertThat(service.retrieve(" a_switch ").switchId()).isEqualTo("A_SWITCH");
        assertThat(service.retrieveAll()).extracting(NipSwitchAccountingConfigurationData::switchId).containsExactly("A_SWITCH",
                "B_SWITCH");
    }

    @Test
    void upsertCreatesNormalizedConfigurationAndReplacesExistingValues() {
        JsonCommand command = command(1, 2, 3, true);
        when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.empty());
        when(glAccountRepository.findOneWithNotFoundDetection(1L)).thenReturn(detailAccount(1));
        when(glAccountRepository.findOneWithNotFoundDetection(2L)).thenReturn(detailAccount(2));
        when(glAccountRepository.findOneWithNotFoundDetection(3L)).thenReturn(detailAccount(3));

        service.upsert(" nibss ", command);

        verify(validator).validateForUpsert("NIBSS", command);
        verify(repository).saveAndFlush(any(NipSwitchAccountingConfigurationEntity.class));

        NipSwitchAccountingConfigurationEntity existing = configuration("NIBSS", true, detailAccount(10), detailAccount(20),
                detailAccount(30));
        when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.of(existing));
        service.upsert("NIBSS", command);

        assertThat(existing.getSwitchPayableGlAccount().getId()).isEqualTo(1L);
        assertThat(existing.getSwitchFeeGlAccount().getId()).isEqualTo(2L);
        assertThat(existing.getCommissionIncomeGlAccount().getId()).isEqualTo(3L);
    }

    @Test
    void rejectsDisabledOrHeaderGlBeforePersistence() {
        JsonCommand command = command(1, 2, 3, true);
        when(glAccountRepository.findOneWithNotFoundDetection(1L)).thenReturn(account(1, HEADER.getValue(), false));

        assertThatThrownBy(() -> service.upsert("NIBSS", command)).isInstanceOf(PlatformApiDataValidationException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(glAccountRepository, never()).findOneWithNotFoundDetection(eq(2L));
    }

    private JsonCommand command(long payableId, long feeId, long incomeId, boolean active) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.longValueOfParameterNamed("switchPayableGlAccountId")).thenReturn(payableId);
        when(command.longValueOfParameterNamed("switchFeeGlAccountId")).thenReturn(feeId);
        when(command.longValueOfParameterNamed("commissionIncomeGlAccountId")).thenReturn(incomeId);
        when(command.booleanPrimitiveValueOfParameterNamed("active")).thenReturn(active);
        return command;
    }

    private NipSwitchAccountingConfigurationEntity configuration(String switchId, boolean active, GLAccount payable, GLAccount fee,
            GLAccount income) {
        return NipSwitchAccountingConfigurationEntity.create(switchId, payable, fee, income, active);
    }

    private GLAccount detailAccount(long id) {
        return account(id, DETAIL.getValue(), false);
    }

    private GLAccount account(long id, int usage, boolean disabled) {
        GLAccount account = new GLAccount().setUsage(usage).setDisabled(disabled);
        account.setId(id);
        return account;
    }
}
