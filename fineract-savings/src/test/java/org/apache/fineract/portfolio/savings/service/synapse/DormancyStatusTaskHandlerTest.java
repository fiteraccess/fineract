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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusResponse;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DormancyStatusTaskHandlerTest {

    private static final String TRACE_ID = "trace-dorm-1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String VALID_PAYLOAD = """
            {
              "traceId": "trace-dorm-1",
              "savingsAccountId": 4242,
              "clientId": 77,
              "officeId": 7,
              "previousSubStatus": "NONE",
              "targetSubStatus": "INACTIVE",
              "effectiveDate": "2026-04-30",
              "transitionReason": "Inactive 90 days",
              "currencyCode": "NGN"
            }
            """;

    @Mock
    private SynapseTransactionClient client;

    private DormancyStatusTaskHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DormancyStatusTaskHandler(client, OBJECT_MAPPER);
    }

    @Test
    void taskType_returnsDormancyStatus() {
        assertThat(handler.taskType()).isEqualTo("DORMANCY_STATUS");
    }

    @Nested
    class Dispatch {

        @Test
        void dispatch_forwardsDeserialisedInstructionToClient() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class)))
                    .thenReturn(new SynapseDormancyStatusResponse(TRACE_ID, "ACCEPTED", null, null, null));

            handler.dispatch(entry);

            ArgumentCaptor<SynapseDormancyStatusInstruction> captor = ArgumentCaptor.forClass(SynapseDormancyStatusInstruction.class);
            verify(client).postDormancyStatus(captor.capture());
            SynapseDormancyStatusInstruction sent = captor.getValue();
            assertThat(sent.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(sent.getSavingsAccountId()).isEqualTo(4242L);
            assertThat(sent.getClientId()).isEqualTo(77L);
            assertThat(sent.getOfficeId()).isEqualTo(7L);
            assertThat(sent.getPreviousSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.NONE);
            assertThat(sent.getTargetSubStatus()).isEqualTo(SavingsAccountSubStatusEnum.INACTIVE);
            assertThat(sent.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 4, 30));
            assertThat(sent.getTransitionReason()).isEqualTo("Inactive 90 days");
            assertThat(sent.getCurrencyCode()).isEqualTo("NGN");
        }

        @Test
        void dispatch_acceptedResponse_returnsNormally() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class)))
                    .thenReturn(new SynapseDormancyStatusResponse(TRACE_ID, "ACCEPTED", "corr-1", null, null));

            handler.dispatch(entry);

            verify(client, times(1)).postDormancyStatus(any(SynapseDormancyStatusInstruction.class));
        }

        @Test
        void dispatch_rejectedWithInflightReason_returnsNormally() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class)))
                    .thenReturn(new SynapseDormancyStatusResponse(TRACE_ID, "REJECTED", null, "ACCOUNT_HAS_INFLIGHT_TRANSACTIONS", 2));

            handler.dispatch(entry);

            verify(client, times(1)).postDormancyStatus(any(SynapseDormancyStatusInstruction.class));
        }

        @Test
        void dispatch_rejectedWithOtherReason_throwsSynapsePostingException() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class)))
                    .thenReturn(new SynapseDormancyStatusResponse(TRACE_ID, "REJECTED", null, "VALIDATION_FAILED", null));

            assertThatThrownBy(() -> handler.dispatch(entry)).isInstanceOf(SynapsePostingException.class).hasMessageContaining(TRACE_ID)
                    .hasMessageContaining("VALIDATION_FAILED");
        }

        @Test
        void dispatch_unexpectedStatus_throwsSynapsePostingException() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class)))
                    .thenReturn(new SynapseDormancyStatusResponse(TRACE_ID, "WAT", null, null, null));

            assertThatThrownBy(() -> handler.dispatch(entry)).isInstanceOf(SynapsePostingException.class).hasMessageContaining("WAT");
        }

        @Test
        void dispatch_malformedPayload_throwsSynapsePostingException() {
            OutboxEntry entry = buildEntry("not-json");

            assertThatThrownBy(() -> handler.dispatch(entry)).isInstanceOf(SynapsePostingException.class)
                    .hasMessageContaining("deserialize");
        }

        @Test
        void dispatch_malformedPayload_doesNotInvokeClient() {
            OutboxEntry entry = buildEntry("not-json");

            assertThatThrownBy(() -> handler.dispatch(entry)).isInstanceOf(SynapsePostingException.class);

            verifyNoInteractions(client);
        }

        @Test
        void dispatch_propagatesClientException() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            SynapsePostingException thrown = new SynapsePostingException("upstream failure traceId=" + TRACE_ID);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class))).thenThrow(thrown);

            assertThatThrownBy(() -> handler.dispatch(entry)).isSameAs(thrown);
        }

        @Test
        void dispatch_nullStatusResponse_throwsSynapsePostingException() {
            OutboxEntry entry = buildEntry(VALID_PAYLOAD);
            when(client.postDormancyStatus(any(SynapseDormancyStatusInstruction.class)))
                    .thenReturn(new SynapseDormancyStatusResponse(TRACE_ID, null, null, null, null));

            assertThatThrownBy(() -> handler.dispatch(entry)).isInstanceOf(SynapsePostingException.class).hasMessageContaining(TRACE_ID);
        }
    }

    private static OutboxEntry buildEntry(String payload) {
        return OutboxEntry.builder().id(1L).traceId(TRACE_ID).batchId("batch-1").taskType("DORMANCY_STATUS").accountId(4242L).officeId(7L)
                .payload(payload).build();
    }
}
