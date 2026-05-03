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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SynapseDormancyPostingOutboxWriterTest {

    private static final Long ACCOUNT_ID = 4242L;
    private static final Long OFFICE_ID = 7L;
    private static final Long CLIENT_ID = 77L;
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 4, 30);
    private static final String REASON = "Inactive 90 days";
    private static final String TRACE_ID = "trace-dormancy-1";

    private SynapseInstructionMapper mapper;
    private SynapseOutboxRepository outboxRepository;
    private ObjectMapper objectMapper;
    private SynapseDormancyPostingOutboxWriter writer;
    private SavingsAccount account;
    private SynapseDormancyStatusInstruction stubInstruction;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        mapper = mock(SynapseInstructionMapper.class);
        outboxRepository = mock(SynapseOutboxRepository.class);
        objectMapper = mock(ObjectMapper.class);
        writer = new SynapseDormancyPostingOutboxWriter(mapper, outboxRepository, objectMapper);

        account = mock(SavingsAccount.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.officeId()).thenReturn(OFFICE_ID);

        stubInstruction = SynapseDormancyStatusInstruction.builder().traceId(TRACE_ID).savingsAccountId(ACCOUNT_ID).clientId(CLIENT_ID)
                .officeId(OFFICE_ID).previousSubStatus(SavingsAccountSubStatusEnum.NONE)
                .targetSubStatus(SavingsAccountSubStatusEnum.INACTIVE).effectiveDate(EFFECTIVE).transitionReason(REASON).currencyCode("NGN")
                .build();

        when(mapper.mapDormancyStatus(eq(account), eq(SavingsAccountSubStatusEnum.INACTIVE), eq(EFFECTIVE), eq(REASON)))
                .thenReturn(stubInstruction);
        when(objectMapper.writeValueAsString(any(SynapseDormancyStatusInstruction.class))).thenReturn("{\"traceId\":\"" + TRACE_ID + "\"}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void postDormancy_writesOutboxEntryWithCorrectTaskType() {
        writer.postDormancy(account, SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE, REASON);

        ArgumentCaptor<String> taskTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(taskTypeCaptor.capture(), anyString(), entriesCaptor.capture());

        assertThat(taskTypeCaptor.getValue()).isEqualTo("DORMANCY_STATUS");
        assertThat(entriesCaptor.getValue()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void postDormancy_persistsTraceIdAndAccountFields() {
        writer.postDormancy(account, SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE, REASON);

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(anyString(), anyString(), entriesCaptor.capture());

        OutboxEntry entry = entriesCaptor.getValue().get(0);
        assertThat(entry.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(entry.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(entry.getOfficeId()).isEqualTo(OFFICE_ID);
    }

    @SuppressWarnings("unchecked")
    @Test
    void postDormancy_serialisesMappedInstructionAndStoresJsonAsPayload() throws JsonProcessingException {
        String sentinelJson = "{\"sentinel\":true}";
        when(objectMapper.writeValueAsString(any(SynapseDormancyStatusInstruction.class))).thenReturn(sentinelJson);

        writer.postDormancy(account, SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE, REASON);

        ArgumentCaptor<SynapseDormancyStatusInstruction> instructionCaptor = ArgumentCaptor.forClass(SynapseDormancyStatusInstruction.class);
        verify(objectMapper).writeValueAsString(instructionCaptor.capture());
        assertThat(instructionCaptor.getValue()).isSameAs(stubInstruction);

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(anyString(), anyString(), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue().get(0).getPayload()).isEqualTo(sentinelJson);
    }

    @Test
    void postDormancy_passesNonBlankBatchIdToInsertBatch() {
        writer.postDormancy(account, SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE, REASON);

        ArgumentCaptor<String> batchIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).insertBatch(anyString(), batchIdCaptor.capture(), any());

        assertThat(batchIdCaptor.getValue()).isNotBlank();
    }

    @Test
    void postDormancy_throwsIllegalStateOnSerialisationFailure() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any(SynapseDormancyStatusInstruction.class)))
                .thenThrow(new JsonProcessingException("boom") {});

        assertThatThrownBy(() -> writer.postDormancy(account, SavingsAccountSubStatusEnum.INACTIVE, EFFECTIVE, REASON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(TRACE_ID);
        verifyNoInteractions(outboxRepository);
    }
}
