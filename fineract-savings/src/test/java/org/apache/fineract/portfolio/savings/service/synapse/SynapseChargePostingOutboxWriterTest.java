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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SynapseChargePostingOutboxWriterTest {

    private static final Long CHARGE_ID = 100L;
    private static final Long ACCOUNT_ID = 42L;
    private static final Long OFFICE_ID = 10L;
    private static final String EXTERNAL_ID = "EXT-42";
    private static final String CHARGE_NAME = "Stamp Duty";
    private static final BigDecimal AMOUNT = new BigDecimal("150.00");
    private static final LocalDate TX_DATE = LocalDate.of(2026, 4, 18);
    private static final String CURRENCY = "NGN";

    private SynapseInstructionMapper mapper;
    private SynapseOutboxRepository outboxRepository;
    private ObjectMapper objectMapper;
    private SynapseChargePostingOutboxWriter writer;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        mapper = mock(SynapseInstructionMapper.class);
        outboxRepository = mock(SynapseOutboxRepository.class);
        objectMapper = mock(ObjectMapper.class);
        writer = new SynapseChargePostingOutboxWriter(mapper, outboxRepository, objectMapper);

        SynapseTransactionInstruction stubInstruction = SynapseTransactionInstruction.builder()
                .traceId("trace-abc-123")
                .savingsAccountId(ACCOUNT_ID)
                .officeId(OFFICE_ID)
                .externalId(EXTERNAL_ID)
                .transactionType(SynapseTransactionInstruction.TransactionType.SAVINGS_CHARGE)
                .direction(SynapseTransactionInstruction.Direction.DEBIT)
                .operation(SynapseTransactionInstruction.Operation.POST)
                .amount(AMOUNT)
                .description(CHARGE_NAME)
                .transactionDate(TX_DATE)
                .currencyCode(CURRENCY)
                .batchId("batch-placeholder")
                .build();

        when(mapper.mapCharge(eq(CHARGE_ID), eq(ACCOUNT_ID), eq(OFFICE_ID), eq(EXTERNAL_ID), eq(CHARGE_NAME),
                eq(AMOUNT), eq(TX_DATE), eq(CURRENCY), anyString())).thenReturn(stubInstruction);

        when(objectMapper.writeValueAsString(any(SynapseTransactionInstruction.class)))
                .thenReturn("{\"traceId\":\"trace-abc-123\"}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void postCharge_writesOutboxEntryWithCorrectTaskType() {
        writer.postCharge(CHARGE_ID, ACCOUNT_ID, OFFICE_ID, EXTERNAL_ID, CHARGE_NAME, AMOUNT, TX_DATE, CURRENCY);

        ArgumentCaptor<String> taskTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(taskTypeCaptor.capture(), anyString(), entriesCaptor.capture());

        assertThat(taskTypeCaptor.getValue()).isEqualTo("CHARGE_POSTING");
        assertThat(entriesCaptor.getValue()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void postCharge_generatesUniqueTraceId() {
        writer.postCharge(CHARGE_ID, ACCOUNT_ID, OFFICE_ID, EXTERNAL_ID, CHARGE_NAME, AMOUNT, TX_DATE, CURRENCY);

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(anyString(), anyString(), entriesCaptor.capture());

        OutboxEntry entry = entriesCaptor.getValue().get(0);
        assertThat(entry.getTraceId()).isNotNull();
        assertThat(entry.getTraceId()).isEqualTo("trace-abc-123");
    }

    @Test
    void postCharge_serializesInstructionToJson() throws JsonProcessingException {
        writer.postCharge(CHARGE_ID, ACCOUNT_ID, OFFICE_ID, EXTERNAL_ID, CHARGE_NAME, AMOUNT, TX_DATE, CURRENCY);

        verify(objectMapper).writeValueAsString(any(SynapseTransactionInstruction.class));
    }

    @Test
    void postCharge_passesCorrectFieldsToMapper() {
        writer.postCharge(CHARGE_ID, ACCOUNT_ID, OFFICE_ID, EXTERNAL_ID, CHARGE_NAME, AMOUNT, TX_DATE, CURRENCY);

        verify(mapper).mapCharge(
                eq(CHARGE_ID),
                eq(ACCOUNT_ID),
                eq(OFFICE_ID),
                eq(EXTERNAL_ID),
                eq(CHARGE_NAME),
                eq(AMOUNT),
                eq(TX_DATE),
                eq(CURRENCY),
                anyString());
    }
}
