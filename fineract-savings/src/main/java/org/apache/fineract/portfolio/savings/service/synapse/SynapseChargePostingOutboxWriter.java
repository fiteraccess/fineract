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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;

@Slf4j
@RequiredArgsConstructor
public class SynapseChargePostingOutboxWriter {

    private final SynapseInstructionMapper mapper;
    private final SynapseOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void postCharge(Long savingsAccountId, Long officeId, String externalId, String chargeName, BigDecimal amount,
            LocalDate transactionDate, String currencyCode) {
        String batchId = UUID.randomUUID().toString();

        SynapseTransactionInstruction instruction = mapper.mapCharge(savingsAccountId, officeId, externalId, chargeName, amount,
                transactionDate, currencyCode, batchId);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(instruction);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize charge instruction for traceId: " + instruction.getTraceId(), e);
        }

        OutboxEntry entry = OutboxEntry.builder()
                .traceId(instruction.getTraceId())
                .accountId(savingsAccountId)
                .officeId(officeId)
                .payload(payload)
                .build();

        outboxRepository.insertBatch("CHARGE_POSTING", batchId, List.of(entry));
        log.debug("Batch {}: wrote charge instruction to outbox for account {}", batchId, savingsAccountId);
    }
}
