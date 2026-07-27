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
package org.apache.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NipDepositPreflightTest {

    private final NipSwitchAccountingConfigurationProvider switchConfigurationProvider = Mockito
            .mock(NipSwitchAccountingConfigurationProvider.class);
    private final NipDepositPreflight preflight = new NipDepositPreflight(switchConfigurationProvider);

    @Nested
    class InboundConfiguration {

        @Test
        void requiresTheNormalizedInboundMappingBeforeDepositMutation() {
            preflight.validate("NIBSS");

            verify(switchConfigurationProvider).requireInbound("NIBSS");
        }

        @Test
        void propagatesInboundMappingFailureBeforeDepositMutation() {
            IllegalStateException failure = new IllegalStateException("NIBSS is not configured for inbound NIP");
            doThrow(failure).when(switchConfigurationProvider).requireInbound("NIBSS");

            assertThatThrownBy(() -> preflight.validate("NIBSS")).isSameAs(failure);
        }
    }
}
