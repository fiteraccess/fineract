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
package org.apache.fineract.portfolio.paymenttype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeUpdateRequest;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.apache.fineract.portfolio.paymenttype.mapper.PaymentTypeCreateRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentTypeWriteServiceImplTest {

    private static final long BRIDGE_PAYMENT_TYPE_ID = 9003L;

    @Mock
    private PaymentTypeRepository repository;
    @Mock
    private PaymentTypeCreateRequestMapper createRequestMapper;
    @Mock
    private ConfigurationDomainService configurationDomainService;

    private PaymentTypeWriteServiceImpl service;
    private PaymentType bridge;

    @BeforeEach
    void setUp() {
        service = new PaymentTypeWriteServiceImpl(repository, createRequestMapper, configurationDomainService);
        bridge = new PaymentType();
        bridge.setId(BRIDGE_PAYMENT_TYPE_ID);
        bridge.setName("Intrabank Transfer Fineract to Flexcube");
        bridge.setCodeName("INTRABANK_FINERACT_TO_FLEXCUBE");
        bridge.setIsSystemDefined(true);
        when(repository.findById(BRIDGE_PAYMENT_TYPE_ID)).thenReturn(Optional.of(bridge));
    }

    @Nested
    class WhenLocked {

        @BeforeEach
        void setUp() {
            when(configurationDomainService.isSystemPaymentTypeFundSourceLockEnabled()).thenReturn(true);
        }

        @Test
        void refusesAnUpdateThatOmitsTheSystemDefinedFlag() {
            var request = PaymentTypeUpdateRequest.builder().id(BRIDGE_PAYMENT_TYPE_ID).name("Renamed")
                    .codeName("INTRABANK_FINERACT_TO_FLEXCUBE").build();

            assertThatThrownBy(() -> service.updatePaymentType(request)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                    .hasFieldOrPropertyWithValue("globalisationMessageCode", "error.msg.payment.type.system.defined.locked");
            verify(repository, never()).saveAndFlush(bridge);
            assertThat(bridge.getIsSystemDefined()).isTrue();
        }
    }

    @Test
    void allowsAnUpdateThatKeepsTheTypeSystemDefined() {
        var request = PaymentTypeUpdateRequest.builder().id(BRIDGE_PAYMENT_TYPE_ID).name("Renamed")
                .codeName("INTRABANK_FINERACT_TO_FLEXCUBE").isSystemDefined(true).build();

        service.updatePaymentType(request);

        verify(repository).saveAndFlush(bridge);
        assertThat(bridge.getName()).isEqualTo("Renamed");
        verifyNoInteractions(configurationDomainService);
    }

    @Test
    void allowsMakingTheTypeUserDefinedWhenUnlocked() {
        when(configurationDomainService.isSystemPaymentTypeFundSourceLockEnabled()).thenReturn(false);
        var request = PaymentTypeUpdateRequest.builder().id(BRIDGE_PAYMENT_TYPE_ID).name("Renamed")
                .codeName("INTRABANK_FINERACT_TO_FLEXCUBE").build();

        service.updatePaymentType(request);

        verify(repository).saveAndFlush(bridge);
        assertThat(bridge.getIsSystemDefined()).isFalse();
    }
}
