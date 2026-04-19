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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostingResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChargePostingTaskHandlerTest {

    @Mock
    private SynapseTransactionClient client;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private ChargePostingTaskHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChargePostingTaskHandler(client, objectMapper);
    }

    @Test
    void taskType_returnsChargePosting() {
        assertThat(handler.taskType()).isEqualTo("CHARGE_POSTING");
    }

    @Nested
    class Dispatch {

        @Test
        void dispatch_deserializesPayloadAndPostsBatch() {
            OutboxEntry entry = buildEntry(validPayload());
            whenClientAccepts();

            handler.dispatch(entry);

            ArgumentCaptor<SynapseInterestPostingBatch> captor = ArgumentCaptor.forClass(SynapseInterestPostingBatch.class);
            verify(client).postBatch(captor.capture());

            SynapseInterestPostingBatch batch = captor.getValue();
            assertThat(batch.getBatchId()).isEqualTo("batch-1");
            assertThat(batch.getPostingDate()).isEqualTo(LocalDate.of(2026, 3, 20));
            assertThat(batch.getTransactions()).hasSize(1);
        }

        @Test
        void dispatch_buildsBatchWithTotalCountOne() {
            OutboxEntry entry = buildEntry(validPayload());
            whenClientAccepts();

            handler.dispatch(entry);

            ArgumentCaptor<SynapseInterestPostingBatch> captor = ArgumentCaptor.forClass(SynapseInterestPostingBatch.class);
            verify(client).postBatch(captor.capture());

            assertThat(captor.getValue().getTotalCount()).isEqualTo(1);
        }

        @Test
        void dispatch_throwsOnRejectedResponse() {
            OutboxEntry entry = buildEntry(validPayload());
            SynapseBatchPostingResponse response = new SynapseBatchPostingResponse(
                    "batch-1", 0, 1,
                    List.of(new SynapsePostingResult("trace-1", "REJECTED", null)));
            when(client.postBatch(any(SynapseInterestPostingBatch.class))).thenReturn(response);

            assertThatThrownBy(() -> handler.dispatch(entry))
                    .isInstanceOf(SynapsePostingException.class)
                    .hasMessageContaining("rejected")
                    .hasMessageContaining("trace-1");
        }

        private void whenClientAccepts() {
            SynapseBatchPostingResponse response = new SynapseBatchPostingResponse(
                    "batch-1", 1, 0,
                    List.of(new SynapsePostingResult("trace-1", "ACCEPTED", null)));
            when(client.postBatch(any(SynapseInterestPostingBatch.class))).thenReturn(response);
        }
    }

    private static OutboxEntry buildEntry(String payload) {
        return OutboxEntry.builder()
                .id(1L)
                .traceId("trace-1")
                .batchId("batch-1")
                .taskType("CHARGE_POSTING")
                .accountId(100L)
                .officeId(10L)
                .payload(payload)
                .build();
    }

    private static String validPayload() {
        SynapseTransactionInstruction instruction = SynapseTransactionInstruction.builder()
                .traceId("trace-1")
                .savingsAccountId(100L)
                .officeId(10L)
                .transactionType(SynapseTransactionInstruction.TransactionType.SAVINGS_CHARGE)
                .direction(SynapseTransactionInstruction.Direction.DEBIT)
                .operation(SynapseTransactionInstruction.Operation.POST)
                .amount(new BigDecimal("50.00"))
                .transactionDate(LocalDate.of(2026, 3, 20))
                .currencyCode("NGN")
                .batchId("batch-1")
                .build();
        try {
            return new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(instruction);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
