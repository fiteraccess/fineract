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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostingResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;

@Slf4j
@RequiredArgsConstructor
public class ChargePostingTaskHandler implements SynapseTaskHandler {

    private static final String ACCEPTED_STATUS = "ACCEPTED";

    private final SynapseTransactionClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "CHARGE_POSTING";
    }

    @Override
    public void dispatch(OutboxEntry entry) {
        SynapseTransactionInstruction instruction = deserializePayload(entry);

        SynapseInterestPostingBatch batch = SynapseInterestPostingBatch.builder().batchId(entry.getBatchId())
                .postingDate(instruction.getTransactionDate()).totalCount(1).transactions(List.of(instruction)).build();

        SynapseBatchPostingResponse response = client.postBatch(batch);

        validateResponse(response, entry);
    }

    private SynapseTransactionInstruction deserializePayload(OutboxEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), SynapseTransactionInstruction.class);
        } catch (Exception e) {
            throw new SynapsePostingException("Failed to deserialize payload for outbox entry id=" + entry.getId(), e);
        }
    }

    private void validateResponse(SynapseBatchPostingResponse response, OutboxEntry entry) {
        List<String> rejectedTraceIds = response.getResults().stream().filter(r -> !ACCEPTED_STATUS.equals(r.getStatus()))
                .map(SynapsePostingResult::getTraceId).collect(Collectors.toList());

        if (!rejectedTraceIds.isEmpty()) {
            throw new SynapsePostingException(
                    "Synapse rejected instructions for outbox entry id=" + entry.getId() + ", traceIds=" + rejectedTraceIds);
        }
    }
}
