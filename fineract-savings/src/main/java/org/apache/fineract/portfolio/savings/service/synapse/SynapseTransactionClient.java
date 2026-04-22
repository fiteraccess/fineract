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

    private final RestTemplate restTemplate;
    private final String postUrl;
    private final String apiKey;

    public SynapseTransactionClient(RestTemplate restTemplate, String baseUrl, String batchEndpoint, String apiKey) {
        this.restTemplate = restTemplate;
        this.postUrl = baseUrl + batchEndpoint;
        this.apiKey = apiKey;
    }

    public SynapseBatchPostingResponse postBatch(SynapseInterestPostingBatch batch) {
        log.debug("Posting batch {} with {} instructions to {}", batch.getBatchId(), batch.getTotalCount(), postUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        }
        HttpEntity<SynapseInterestPostingBatch> request = new HttpEntity<>(batch, headers);

        try {
            ResponseEntity<SynapseBatchPostingResponse> response = restTemplate.postForEntity(postUrl, request,
                    SynapseBatchPostingResponse.class);

            SynapseBatchPostingResponse body = response.getBody();
            log.debug("Batch {} response: accepted={}, failed={}", batch.getBatchId(), body != null ? body.getAccepted() : "null",
                    body != null ? body.getFailed() : "null");
            return body;
        } catch (RestClientResponseException e) {
            throw new SynapsePostingException(
                    "Synapse batch posting failed with HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SynapsePostingException("Synapse batch posting failed: connection error to " + postUrl, e);
        }
    }
}
