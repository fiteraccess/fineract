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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusResponse;

@Slf4j
@RequiredArgsConstructor
public class DormancyStatusTaskHandler implements SynapseTaskHandler {

    private static final String ACCEPTED_STATUS = "ACCEPTED";
    private static final String REJECTED_STATUS = "REJECTED";
    private static final String INFLIGHT_REASON = "ACCOUNT_HAS_INFLIGHT_TRANSACTIONS";

    private final SynapseTransactionClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "DORMANCY_STATUS";
    }

    @Override
    public void dispatch(OutboxEntry entry) {
        SynapseDormancyStatusInstruction instruction = deserializePayload(entry);

        SynapseDormancyStatusResponse response = client.postDormancyStatus(instruction);

        handleResponse(response, instruction);
    }

    private SynapseDormancyStatusInstruction deserializePayload(OutboxEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), SynapseDormancyStatusInstruction.class);
        } catch (Exception e) {
            throw new SynapsePostingException("Failed to deserialize dormancy payload for outbox entry id=" + entry.getId(), e);
        }
    }

    private void handleResponse(SynapseDormancyStatusResponse response, SynapseDormancyStatusInstruction instruction) {
        String status = response.getStatus();
        if (ACCEPTED_STATUS.equals(status)) {
            log.debug("Dormancy status accepted traceId={} savingsAccountId={}", response.getTraceId(), instruction.getSavingsAccountId());
            return;
        }
        if (!REJECTED_STATUS.equals(status)) {
            throw new SynapsePostingException("Unexpected status from Synapse: " + status + " traceId=" + response.getTraceId());
        }
        if (INFLIGHT_REASON.equals(response.getReason())) {
            log.warn("Dormancy status rejected due to inflight transactions traceId={} savingsAccountId={} inflightCount={}",
                    response.getTraceId(), instruction.getSavingsAccountId(), response.getInflightCount());
            // self-healing: dormancy job re-emits next tick if still eligible
            return;
        }
        throw new SynapsePostingException(
                "Synapse rejected dormancy status traceId=" + response.getTraceId() + " reason=" + response.getReason());
    }
}
