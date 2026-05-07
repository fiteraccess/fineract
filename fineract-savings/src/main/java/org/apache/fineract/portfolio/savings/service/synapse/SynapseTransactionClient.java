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

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class SynapseTransactionClient {

    private static final String BATCH_ENDPOINT_PATH = "/api/v1/proxy/savings/interest-postings:batch";
    private static final String DORMANCY_ENDPOINT_PATH = "/v1/proxy/savings/dormancy-statuses";

    private final RestTemplate restTemplate;
    private final String postUrl;
    private final String dormancyUrl;
    private final String apiKey;

    public SynapseTransactionClient(RestTemplate restTemplate, String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "fineract.synapse.api-key (FINERACT_SYNAPSE_API_KEY) must be set when fineract.synapse.enabled=true");
        }
        this.restTemplate = restTemplate;
        this.postUrl = baseUrl + BATCH_ENDPOINT_PATH;
        this.dormancyUrl = baseUrl + DORMANCY_ENDPOINT_PATH;
        this.apiKey = apiKey;
    }

    public SynapseBatchPostingResponse postBatch(SynapseInterestPostingBatch batch) {
        log.debug("Posting batch {} with {} instructions to {}", batch.getBatchId(), batch.getTotalCount(), postUrl);
        SynapseBatchPostingResponse body;
        try {
            body = post(postUrl, batch, SynapseBatchPostingResponse.class);
        } catch (RestClientResponseException e) {
            throw new SynapsePostingException("Synapse posting failed with HTTP " + e.getStatusCode() + " batchId=" + batch.getBatchId()
                    + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SynapsePostingException("Synapse posting failed: connection error to " + postUrl + " batchId=" + batch.getBatchId(),
                    e);
        }
        log.debug("Batch {} response: accepted={}, failed={}", batch.getBatchId(), body != null ? body.getAccepted() : "null",
                body != null ? body.getFailed() : "null");
        return body;
    }

    public SynapseDormancyStatusResponse postDormancyStatus(SynapseDormancyStatusInstruction instruction) {
        log.debug("Posting dormancy status traceId={} to {}", instruction.getTraceId(), dormancyUrl);
        SynapseDormancyStatusResponse body;
        try {
            body = post(dormancyUrl, instruction, SynapseDormancyStatusResponse.class);
        } catch (RestClientResponseException e) {
            throw new SynapsePostingException("Synapse posting failed with HTTP " + e.getStatusCode() + " traceId="
                    + instruction.getTraceId() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SynapsePostingException(
                    "Synapse posting failed: connection error to " + dormancyUrl + " traceId=" + instruction.getTraceId(), e);
        }
        log.debug("Dormancy status traceId={} response: status={}", instruction.getTraceId(), body != null ? body.getStatus() : "null");
        return body;
    }

    private <ReqT, ResT> ResT post(String url, ReqT body, Class<ResT> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        HttpEntity<ReqT> request = new HttpEntity<>(body, headers);
        ResponseEntity<ResT> response = restTemplate.postForEntity(url, request, responseType);
        return response.getBody();
    }
}
