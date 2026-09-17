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
package org.apache.fineract.portfolio.savings.service.synapse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseCreditRestrictionApplier.ApplyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SynapseCreditRestrictionApplierTest {

    private SavingsAccountRepositoryWrapper repository;
    private SynapseCreditRestrictionApplier applier;
    private SavingsAccount account;

    @BeforeEach
    void setUp() {
        repository = mock(SavingsAccountRepositoryWrapper.class);
        applier = new SynapseCreditRestrictionApplier(repository);
        account = new SavingsAccount() {};
    }

    @Nested
    class Restricting {

        @Test
        void setsTheFlagAndPersistsOnce() {
            ApplyResult result = applier.apply(account, true);

            assertThat(account.isSynapseCreditRestricted()).isTrue();
            assertThat(result).isEqualTo(new ApplyResult(true, false));
            verify(repository).saveAndFlush(account);
        }

        @Test
        void aRedeliveredCommandWritesNothing() {
            account.setSynapseCreditRestricted(true);

            ApplyResult result = applier.apply(account, true);

            assertThat(result).isEqualTo(new ApplyResult(true, true));
            verifyNoInteractions(repository);
        }
    }

    @Nested
    class ReadingTheDecision {

        @Test
        void trueAndFalseAreTakenAsStated() {
            assertThat(SynapseCreditRestrictionApplier.requiredRestricted(command("{\"restricted\":true}"))).isTrue();
            assertThat(SynapseCreditRestrictionApplier.requiredRestricted(command("{\"restricted\":false}"))).isFalse();
        }

        @Test
        void anEmptyReplayIsRefusedRatherThanReadAsALift() {
            assertThatThrownBy(() -> SynapseCreditRestrictionApplier.requiredRestricted(command("{}")))
                    .isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void anExplicitNullIsRefusedToo() {
            assertThatThrownBy(() -> SynapseCreditRestrictionApplier.requiredRestricted(command("{\"restricted\":null}")))
                    .isInstanceOf(PlatformApiDataValidationException.class);
        }

        private JsonCommand command(String json) {
            FromJsonHelper helper = new FromJsonHelper();
            return new JsonCommand(1L, helper.parse(json), helper);
        }
    }

    @Nested
    class Lifting {

        @Test
        void clearsTheFlagAndPersists() {
            account.setSynapseCreditRestricted(true);

            ApplyResult result = applier.apply(account, false);

            assertThat(account.isSynapseCreditRestricted()).isFalse();
            assertThat(result).isEqualTo(new ApplyResult(false, false));
            verify(repository).saveAndFlush(account);
        }

        @Test
        void anAlreadyUnrestrictedAccountIsLeftAlone() {
            ApplyResult result = applier.apply(account, false);

            assertThat(result).isEqualTo(new ApplyResult(false, true));
            verifyNoInteractions(repository);
        }
    }
}
