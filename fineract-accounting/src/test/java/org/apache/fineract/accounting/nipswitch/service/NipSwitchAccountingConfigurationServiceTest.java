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

import static org.apache.fineract.accounting.glaccount.domain.GLAccountType.ASSET;
import static org.apache.fineract.accounting.glaccount.domain.GLAccountUsage.DETAIL;
import static org.apache.fineract.accounting.glaccount.domain.GLAccountUsage.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.nipswitch.data.NipSwitchAccountingConfigurationData;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationEntity;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationRepository;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingDirection;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationDirectionException;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationInactiveException;
import org.apache.fineract.accounting.nipswitch.exception.NipSwitchAccountingConfigurationNotFoundException;
import org.apache.fineract.accounting.nipswitch.serialization.NipSwitchAccountingConfigurationValidator;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

    @Nested
    class Resolution {

        @Test
        void resolvesEachSupportedDirectionUsingNormalizedSwitchId() {
            NipSwitchAccountingConfigurationEntity both = configuration("NIBSS", NipSwitchAccountingDirection.BOTH, true, detailAccount(1),
                    detailAccount(2), detailAccount(3), assetDetailAccount(4));
            when(repository.findBySwitchIdAndActiveTrue("NIBSS")).thenReturn(Optional.of(both));

            assertThat(service.requireOutbound(" nibss "))
                    .isEqualTo(new NipSwitchAccountingConfigurationProvider.OutboundConfiguration("NIBSS", 1L, 2L, 3L));
            assertThat(service.requireInbound("nibss"))
                    .isEqualTo(new NipSwitchAccountingConfigurationProvider.InboundConfiguration("NIBSS", 4L));
        }

        @Test
        void rejectsUnsupportedDirectionWithoutUsingTheOtherMapping() {
            NipSwitchAccountingConfigurationEntity inbound = configuration("UPSL", NipSwitchAccountingDirection.INBOUND, true, null, null,
                    null, assetDetailAccount(4));
            when(repository.findBySwitchIdAndActiveTrue("UPSL")).thenReturn(Optional.of(inbound));

            assertThatThrownBy(() -> service.requireOutbound("upsl"))
                    .isInstanceOf(NipSwitchAccountingConfigurationDirectionException.class);
        }

        @Test
        void distinguishesInactiveFromMissingInboundConfiguration() {
            NipSwitchAccountingConfigurationEntity inactive = configuration("NIBSS", NipSwitchAccountingDirection.INBOUND, false, null,
                    null, null, assetDetailAccount(4));
            when(repository.findBySwitchIdAndActiveTrue("NIBSS")).thenReturn(Optional.empty());
            when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> service.requireInbound("nibss")).isInstanceOf(NipSwitchAccountingConfigurationInactiveException.class);

            when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireInbound("nibss")).isInstanceOf(NipSwitchAccountingConfigurationNotFoundException.class);
        }
    }

    @Nested
    class Management {

        @Test
        void retrievesOneAndListsDirectionalConfigurationsInRepositoryOrder() {
            NipSwitchAccountingConfigurationEntity first = configuration("A_SWITCH", NipSwitchAccountingDirection.OUTBOUND, true,
                    detailAccount(1), detailAccount(2), detailAccount(3), null);
            NipSwitchAccountingConfigurationEntity second = configuration("B_SWITCH", NipSwitchAccountingDirection.INBOUND, false, null,
                    null, null, assetDetailAccount(4));
            when(repository.findBySwitchId("A_SWITCH")).thenReturn(Optional.of(first));
            when(repository.findAllByOrderBySwitchIdAsc()).thenReturn(List.of(first, second));

            NipSwitchAccountingConfigurationData data = service.retrieve(" a_switch ");
            assertThat(data.direction()).isEqualTo(NipSwitchAccountingDirection.OUTBOUND);
            assertThat(data.switchReceivableGlAccountId()).isNull();
            assertThat(service.retrieveAll()).extracting(NipSwitchAccountingConfigurationData::switchId).containsExactly("A_SWITCH",
                    "B_SWITCH");
        }

        @Test
        void replacesAnExistingConfigurationWithItsCompleteDirectionalMapping() {
            JsonCommand command = command(NipSwitchAccountingDirection.INBOUND, null, null, null, 4L, true);
            NipSwitchAccountingConfigurationEntity existing = configuration("NIBSS", NipSwitchAccountingDirection.OUTBOUND, true,
                    detailAccount(1), detailAccount(2), detailAccount(3), null);
            when(repository.findBySwitchId("NIBSS")).thenReturn(Optional.of(existing));
            when(glAccountRepository.findOneWithNotFoundDetection(4L)).thenReturn(assetDetailAccount(4));

            service.upsert(" nibss ", command);

            verify(validator).validateForUpsert("NIBSS", command);
            verify(glAccountRepository).findOneWithNotFoundDetection(eq(4L));
            assertThat(existing.getDirection()).isEqualTo(NipSwitchAccountingDirection.INBOUND);
            assertThat(existing.getSwitchPayableGlAccount()).isNull();
            assertThat(existing.getSwitchReceivableGlAccount().getId()).isEqualTo(4L);
        }

        @Test
        void rejectsAReceivableThatIsNotAnEnabledDetailAssetBeforePersistence() {
            JsonCommand command = command(NipSwitchAccountingDirection.INBOUND, null, null, null, 4L, true);
            when(glAccountRepository.findOneWithNotFoundDetection(4L)).thenReturn(detailAccount(4));

            assertThatThrownBy(() -> service.upsert("NIBSS", command)).isInstanceOf(PlatformApiDataValidationException.class);
            verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        }

        @Test
        void rejectsDisabledAndHeaderReceivableAccountsBeforePersistence() {
            JsonCommand command = command(NipSwitchAccountingDirection.INBOUND, null, null, null, 4L, true);
            when(glAccountRepository.findOneWithNotFoundDetection(4L)).thenReturn(account(4, HEADER.getValue(), ASSET.getValue(), false));

            assertThatThrownBy(() -> service.upsert("NIBSS", command)).isInstanceOf(PlatformApiDataValidationException.class);

            when(glAccountRepository.findOneWithNotFoundDetection(4L)).thenReturn(account(4, DETAIL.getValue(), ASSET.getValue(), true));
            assertThatThrownBy(() -> service.upsert("NIBSS", command)).isInstanceOf(PlatformApiDataValidationException.class);
            verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        }
    }

    private JsonCommand command(NipSwitchAccountingDirection direction, Long payableId, Long feeId, Long incomeId, Long receivableId,
            boolean active) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.stringValueOfParameterNamed("direction")).thenReturn(direction.name());
        when(command.longValueOfParameterNamed("switchPayableGlAccountId")).thenReturn(payableId);
        when(command.longValueOfParameterNamed("switchFeeGlAccountId")).thenReturn(feeId);
        when(command.longValueOfParameterNamed("commissionIncomeGlAccountId")).thenReturn(incomeId);
        when(command.longValueOfParameterNamed("switchReceivableGlAccountId")).thenReturn(receivableId);
        when(command.booleanPrimitiveValueOfParameterNamed("active")).thenReturn(active);
        return command;
    }

    private NipSwitchAccountingConfigurationEntity configuration(String switchId, NipSwitchAccountingDirection direction, boolean active,
            GLAccount payable, GLAccount fee, GLAccount income, GLAccount receivable) {
        return NipSwitchAccountingConfigurationEntity.create(switchId, direction, payable, fee, income, receivable, active);
    }

    private GLAccount detailAccount(long id) {
        return account(id, DETAIL.getValue(), GLAccountType.LIABILITY.getValue(), false);
    }

    private GLAccount assetDetailAccount(long id) {
        return account(id, DETAIL.getValue(), ASSET.getValue(), false);
    }

    private GLAccount account(long id, int usage, int type, boolean disabled) {
        GLAccount account = new GLAccount().setUsage(usage).setType(type).setDisabled(disabled);
        account.setId(id);
        return account;
    }
}
