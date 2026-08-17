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

import static org.apache.fineract.accounting.glaccount.domain.GLAccountType.LIABILITY;
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
import org.apache.fineract.accounting.aggregatoraccounting.data.AggregatorAccountingConfigurationData;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationEntity;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationProvider;
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationRepository;
import org.apache.fineract.accounting.aggregatoraccounting.exception.AggregatorAccountingConfigurationInactiveException;
import org.apache.fineract.accounting.aggregatoraccounting.exception.AggregatorAccountingConfigurationNotFoundException;
import org.apache.fineract.accounting.aggregatoraccounting.serialization.AggregatorAccountingConfigurationValidator;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AggregatorAccountingConfigurationServiceTest {

    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final AggregatorAccountingConfigurationRepository repository = mock(AggregatorAccountingConfigurationRepository.class);
    private final GLAccountRepositoryWrapper glAccountRepository = mock(GLAccountRepositoryWrapper.class);
    private final AggregatorAccountingConfigurationValidator validator = mock(AggregatorAccountingConfigurationValidator.class);
    private AggregatorAccountingConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new AggregatorAccountingConfigurationService(context, repository, glAccountRepository, validator);
    }

    @Nested
    class Resolution {

        @Test
        void resolvesConfigurationUsingNormalizedAggregatorCode() {
            AggregatorAccountingConfigurationEntity entity = configuration("CORALPAY", true, detailAccount(1), detailAccount(2),
                    detailAccount(3));
            when(repository.findByAggregatorCodeAndActiveTrue("CORALPAY")).thenReturn(Optional.of(entity));

            AggregatorAccountingConfigurationProvider.Configuration configuration = service.requireConfiguration(" coralpay ");

            assertThat(configuration).isEqualTo(new AggregatorAccountingConfigurationProvider.Configuration("CORALPAY", 1L, 2L, 3L));
        }

        @Test
        void distinguishesInactiveFromMissingConfiguration() {
            AggregatorAccountingConfigurationEntity inactive = configuration("CORALPAY", false, detailAccount(1), detailAccount(2),
                    detailAccount(3));
            when(repository.findByAggregatorCodeAndActiveTrue("CORALPAY")).thenReturn(Optional.empty());
            when(repository.findByAggregatorCode("CORALPAY")).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> service.requireConfiguration("coralpay"))
                    .isInstanceOf(AggregatorAccountingConfigurationInactiveException.class);

            when(repository.findByAggregatorCode("CORALPAY")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireConfiguration("coralpay"))
                    .isInstanceOf(AggregatorAccountingConfigurationNotFoundException.class);
        }
    }

    @Nested
    class Management {

        @Test
        void retrievesOneAndListsConfigurationsInRepositoryOrder() {
            AggregatorAccountingConfigurationEntity first = configuration("CORALPAY", true, detailAccount(1), detailAccount(2),
                    detailAccount(3));
            AggregatorAccountingConfigurationEntity second = configuration("NOMIWORLD", false, detailAccount(4), detailAccount(5),
                    detailAccount(6));
            when(repository.findByAggregatorCode("CORALPAY")).thenReturn(Optional.of(first));
            when(repository.findAllByOrderByAggregatorCodeAsc()).thenReturn(List.of(first, second));

            AggregatorAccountingConfigurationData data = service.retrieve(" coralpay ");
            assertThat(data.convenienceFeeIncomeGlAccountId()).isEqualTo(3L);
            assertThat(service.retrieveAll()).extracting(AggregatorAccountingConfigurationData::aggregatorCode).containsExactly("CORALPAY",
                    "NOMIWORLD");
        }

        @Test
        void replacesAnExistingConfigurationWithItsCompleteGlMapping() {
            JsonCommand command = command(1L, 2L, 3L, true);
            AggregatorAccountingConfigurationEntity existing = configuration("CORALPAY", true, detailAccount(9), detailAccount(9),
                    detailAccount(9));
            when(repository.findByAggregatorCode("CORALPAY")).thenReturn(Optional.of(existing));
            when(glAccountRepository.findOneWithNotFoundDetection(1L)).thenReturn(detailAccount(1));
            when(glAccountRepository.findOneWithNotFoundDetection(2L)).thenReturn(detailAccount(2));
            when(glAccountRepository.findOneWithNotFoundDetection(3L)).thenReturn(detailAccount(3));

            service.upsert(" coralpay ", command);

            verify(validator).validateForUpsert("CORALPAY", command);
            verify(glAccountRepository).findOneWithNotFoundDetection(eq(3L));
            assertThat(existing.getAggregatorPayableGlAccount().getId()).isEqualTo(1L);
            assertThat(existing.getConvenienceFeeIncomeGlAccount().getId()).isEqualTo(3L);
        }

        @Test
        void rejectsADisabledOrHeaderGlAccountBeforePersistence() {
            JsonCommand command = command(1L, 2L, 3L, true);
            when(glAccountRepository.findOneWithNotFoundDetection(1L))
                    .thenReturn(account(1, HEADER.getValue(), LIABILITY.getValue(), false));

            assertThatThrownBy(() -> service.upsert("CORALPAY", command)).isInstanceOf(PlatformApiDataValidationException.class);

            when(glAccountRepository.findOneWithNotFoundDetection(1L))
                    .thenReturn(account(1, DETAIL.getValue(), LIABILITY.getValue(), true));
            assertThatThrownBy(() -> service.upsert("CORALPAY", command)).isInstanceOf(PlatformApiDataValidationException.class);
            verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        }

        @Test
        void resolvesTheNowMandatoryConvenienceFeeAccountOnEveryUpsert() {
            JsonCommand command = command(1L, 2L, 3L, true);
            when(glAccountRepository.findOneWithNotFoundDetection(1L)).thenReturn(detailAccount(1));
            when(glAccountRepository.findOneWithNotFoundDetection(2L)).thenReturn(detailAccount(2));
            when(glAccountRepository.findOneWithNotFoundDetection(3L)).thenReturn(detailAccount(3));

            service.upsert("CORALPAY", command);

            verify(glAccountRepository).findOneWithNotFoundDetection(eq(3L));
        }
    }

    private JsonCommand command(Long payableId, Long commissionId, Long convenienceFeeId, boolean active) {
        JsonCommand command = mock(JsonCommand.class);
        when(command.longValueOfParameterNamed("aggregatorPayableGlAccountId")).thenReturn(payableId);
        when(command.longValueOfParameterNamed("commissionIncomeGlAccountId")).thenReturn(commissionId);
        when(command.longValueOfParameterNamed("convenienceFeeIncomeGlAccountId")).thenReturn(convenienceFeeId);
        when(command.booleanPrimitiveValueOfParameterNamed("active")).thenReturn(active);
        return command;
    }

    private AggregatorAccountingConfigurationEntity configuration(String aggregatorCode, boolean active, GLAccount payable,
            GLAccount commission, GLAccount convenienceFee) {
        return AggregatorAccountingConfigurationEntity.create(aggregatorCode, payable, commission, convenienceFee, active);
    }

    private GLAccount detailAccount(long id) {
        return account(id, DETAIL.getValue(), LIABILITY.getValue(), false);
    }

    private GLAccount account(long id, int usage, int type, boolean disabled) {
        GLAccount account = new GLAccount().setUsage(usage).setType(type).setDisabled(disabled);
        account.setId(id);
        return account;
    }
}
