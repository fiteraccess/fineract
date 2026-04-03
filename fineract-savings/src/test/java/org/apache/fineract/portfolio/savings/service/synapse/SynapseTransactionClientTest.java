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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostingResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class SynapseTransactionClientTest {

    private static final String BASE_URL = "http://synapse:8080";
    private static final String BATCH_ENDPOINT = "/api/v1/proxy/savings/interest-postings:batch";
    private static final String FULL_URL = BASE_URL + BATCH_ENDPOINT;

    private MockRestServiceServer mockServer;
    private SynapseTransactionClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(List.of(new MappingJackson2HttpMessageConverter(objectMapper)));

        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new SynapseTransactionClient(restTemplate, BASE_URL, BATCH_ENDPOINT, "Bearer test-token");
    }

    @Test
    void successfulPostReturnsParsedResponse() throws Exception {
        SynapseInterestPostingBatch batch = buildBatch("batch-1");

        SynapseBatchPostingResponse expectedResponse = new SynapseBatchPostingResponse(
                "batch-1", 1, 0,
                List.of(new SynapsePostingResult("trace-1", "ACCEPTED", "corr-1")));

        mockServer.expect(requestTo(FULL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(objectMapper.writeValueAsString(expectedResponse), MediaType.APPLICATION_JSON));

        SynapseBatchPostingResponse result = client.postBatch(batch);

        assertThat(result.getBatchId()).isEqualTo("batch-1");
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getResults()).hasSize(1);
        assertThat(result.getResults().get(0).getTraceId()).isEqualTo("trace-1");
        assertThat(result.getResults().get(0).getStatus()).isEqualTo("ACCEPTED");
        assertThat(result.getResults().get(0).getCorrelationId()).isEqualTo("corr-1");
        mockServer.verify();
    }

    @Test
    void httpServerErrorThrowsSynapsePostingException() {
        SynapseInterestPostingBatch batch = buildBatch("batch-2");

        mockServer.expect(requestTo(FULL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("Internal Server Error"));

        assertThatThrownBy(() -> client.postBatch(batch))
                .isInstanceOf(SynapsePostingException.class)
                .hasMessageContaining("HTTP 500");
        mockServer.verify();
    }

    @Test
    void requestBodyContainsBatchFields() throws Exception {
        SynapseInterestPostingBatch batch = buildBatch("batch-3");

        SynapseBatchPostingResponse response = new SynapseBatchPostingResponse("batch-3", 1, 0, List.of());

        mockServer.expect(requestTo(FULL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"batchId\":\"batch-3\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"totalCount\":1")))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        client.postBatch(batch);
        mockServer.verify();
    }

    @Test
    void requestIncludesAuthorizationHeader() throws Exception {
        SynapseInterestPostingBatch batch = buildBatch("batch-auth");
        SynapseBatchPostingResponse response = new SynapseBatchPostingResponse("batch-auth", 1, 0, List.of());

        mockServer.expect(requestTo(FULL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        client.postBatch(batch);
        mockServer.verify();
    }

    private static SynapseInterestPostingBatch buildBatch(String batchId) {
        SynapseTransactionInstruction instruction = SynapseTransactionInstruction.builder()
                .traceId("trace-1")
                .savingsAccountId(100L)
                .officeId(10L)
                .transactionType(SynapseTransactionInstruction.TransactionType.INTEREST_POSTING)
                .direction(SynapseTransactionInstruction.Direction.CREDIT)
                .operation(SynapseTransactionInstruction.Operation.POST)
                .amount(new BigDecimal("250.00"))
                .transactionDate(LocalDate.of(2026, 3, 20))
                .currencyCode("NGN")
                .batchId(batchId)
                .build();

        return SynapseInterestPostingBatch.builder()
                .batchId(batchId)
                .postingDate(LocalDate.of(2026, 3, 20))
                .totalCount(1)
                .transactions(List.of(instruction))
                .build();
    }
}

