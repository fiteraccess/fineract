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
package org.apache.fineract.portfolio.savings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SynapseOutboxApiResourceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private AppUser user;
    @Mock
    private SynapseOutboxRepository repository;

    private SynapseOutboxApiResource resource;

    @BeforeEach
    void setUp() {
        when(context.authenticatedUser()).thenReturn(user);
        when(user.getUsername()).thenReturn("ops");
        resource = new SynapseOutboxApiResource(context, repository, new FromJsonHelper());
    }

    @Nested
    class CancelOne {

        @Test
        void cancelsTheDeadEntryWithTheOperatorsNameAndReason() {
            when(repository.cancelDeadEntry(36937L, "ops", "reversal Synapse can never apply")).thenReturn(1);

            String response = resource.cancelDeadEntry(36937L, "{\"reason\":\"reversal Synapse can never apply\"}");

            assertThat(response).isEqualTo("{\"resourceId\": 36937, \"updated\": 1}");
        }

        @Test
        void aBlankReasonIsRefusedAndCancelsNothing() {
            assertThrows(PlatformApiDataValidationException.class, () -> resource.cancelDeadEntry(36937L, "{\"reason\":\" \"}"));
            verify(repository, never()).cancelDeadEntry(any(), any(), any());
        }

        @Test
        void aUserWithoutTheAuthorityIsRefused() {
            when(user.hasNotPermissionForAnyOf("ALL_FUNCTIONS")).thenReturn(true);

            assertThrows(NoAuthorizationException.class, () -> resource.cancelDeadEntry(36937L, "{\"reason\":\"x\"}"));
            verify(repository, never()).cancelDeadEntry(any(), any(), any());
        }
    }

    @Nested
    class CancelBefore {

        @Test
        void cancelsTheTaskTypesDeadEntriesCreatedBeforeTheDate() {
            when(repository.cancelDeadEntriesBefore("INTEREST_POSTING", Instant.parse("2026-10-08T00:00:00Z"), "ops", "QA cleanup"))
                    .thenReturn(23);

            String response = resource.cancelDeadEntries(
                    "{\"taskType\":\"INTEREST_POSTING\",\"createdBefore\":\"2026-10-08\",\"reason\":\"QA cleanup\"}");

            assertThat(response).isEqualTo("{\"taskType\": \"INTEREST_POSTING\", \"updated\": 23}");
        }

        @Test
        void aMissingDateIsRefusedAndCancelsNothing() {
            assertThrows(PlatformApiDataValidationException.class,
                    () -> resource.cancelDeadEntries("{\"taskType\":\"INTEREST_POSTING\",\"reason\":\"QA cleanup\"}"));
            verify(repository, never()).cancelDeadEntriesBefore(any(), any(), any(), any());
        }
    }
}
