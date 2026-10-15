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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadGateway;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBusinessDateResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseMirrorRun;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostingResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseReplayStatus;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseSettlementStatus;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseZeroCheck;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

class SynapseTransactionClientTest {

    private static final String BASE_URL = "http://synapse:8080";
    private static final String BATCH_ENDPOINT_PATH = "/api/v1/proxy/savings/interest-postings:batch";
    private static final String DORMANCY_ENDPOINT_PATH = "/api/v1/proxy/savings/dormancy-statuses";
    private static final String FULL_URL = BASE_URL + BATCH_ENDPOINT_PATH;
    private static final String TENANT_HEADER = "Fineract-Platform-TenantId";
    private static final String TENANT_ID = "default";

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
        client = new SynapseTransactionClient(restTemplate, BASE_URL, "Bearer test-token");
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, TENANT_ID, "Default Tenant", "Africa/Lagos", null));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.clearTenant();
    }

    @Test
    void successfulPostReturnsParsedResponse() throws Exception {
        SynapseInterestPostingBatch batch = buildBatch("batch-1");

        SynapseBatchPostingResponse expectedResponse = new SynapseBatchPostingResponse("batch-1", 1, 0,
                List.of(new SynapsePostingResult("trace-1", "ACCEPTED", "corr-1")));

        mockServer.expect(requestTo(FULL_URL)).andExpect(method(HttpMethod.POST))
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

        mockServer.expect(requestTo(FULL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("Internal Server Error"));

        assertThatThrownBy(() -> client.postBatch(batch)).isInstanceOf(SynapsePostingException.class).hasMessageContaining("HTTP 500");
        mockServer.verify();
    }

    @Test
    void requestBodyContainsBatchFields() throws Exception {
        SynapseInterestPostingBatch batch = buildBatch("batch-3");

        SynapseBatchPostingResponse response = new SynapseBatchPostingResponse("batch-3", 1, 0, List.of());

        mockServer.expect(requestTo(FULL_URL)).andExpect(method(HttpMethod.POST))
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

        mockServer.expect(requestTo(FULL_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        client.postBatch(batch);
        mockServer.verify();
    }

    @Test
    void requestIncludesTenantHeaderFromContext() throws Exception {
        SynapseInterestPostingBatch batch = buildBatch("batch-tenant");
        SynapseBatchPostingResponse response = new SynapseBatchPostingResponse("batch-tenant", 1, 0, List.of());

        mockServer.expect(requestTo(FULL_URL)).andExpect(method(HttpMethod.POST)).andExpect(header(TENANT_HEADER, TENANT_ID))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        client.postBatch(batch);
        mockServer.verify();
    }

    @Test
    void missingTenantContextFailsBeforeAnyRequest() {
        ThreadLocalContextUtil.clearTenant();
        SynapseInterestPostingBatch batch = buildBatch("batch-no-tenant");

        assertThatThrownBy(() -> client.postBatch(batch)).isInstanceOf(SynapsePostingException.class)
                .hasMessageContaining("tenant context");
        mockServer.verify();
    }

    @Test
    void constructorThrowsWhenApiKeyIsBlank() {
        RestTemplate restTemplate = new RestTemplate();
        assertThatThrownBy(() -> new SynapseTransactionClient(restTemplate, BASE_URL, "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fineract.synapse.api-key");
    }

    @Test
    void constructorThrowsWhenApiKeyIsNull() {
        RestTemplate restTemplate = new RestTemplate();
        assertThatThrownBy(() -> new SynapseTransactionClient(restTemplate, BASE_URL, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fineract.synapse.api-key");
    }

    @Test
    void constructorThrowsWhenApiKeyIsWhitespaceOnly() {
        RestTemplate restTemplate = new RestTemplate();
        assertThatThrownBy(() -> new SynapseTransactionClient(restTemplate, BASE_URL, "   ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fineract.synapse.api-key");
    }

    @Nested
    class PostDormancyStatus {

        private static final String FULL_DORMANCY_URL = BASE_URL + DORMANCY_ENDPOINT_PATH;
        private static final String TRACE_ID = "trace-dorm-1";

        @Test
        void postDormancyStatus_postsSingleInstructionToConfiguredEndpoint() throws Exception {
            SynapseDormancyStatusInstruction instruction = buildInstruction(TRACE_ID);
            SynapseDormancyStatusResponse expected = new SynapseDormancyStatusResponse(TRACE_ID, "ACCEPTED", "corr-1", null, null);

            mockServer.expect(requestTo(FULL_DORMANCY_URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"traceId\":\"" + TRACE_ID + "\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"savingsAccountId\":4242")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"targetSubStatus\":\"INACTIVE\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"effectiveDate\":[2026,4,30]")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"currencyCode\":\"NGN\"")))
                    .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

            SynapseDormancyStatusResponse result = client.postDormancyStatus(instruction);

            assertThat(result.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(result.getStatus()).isEqualTo("ACCEPTED");
            assertThat(result.getCorrelationId()).isEqualTo("corr-1");
            mockServer.verify();
        }

        @Test
        void postDormancyStatus_includesAuthorizationHeaderWhenApiKeyConfigured() throws Exception {
            SynapseDormancyStatusInstruction instruction = buildInstruction(TRACE_ID);
            SynapseDormancyStatusResponse expected = new SynapseDormancyStatusResponse(TRACE_ID, "ACCEPTED", null, null, null);

            mockServer.expect(requestTo(FULL_DORMANCY_URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token")).andExpect(header(TENANT_HEADER, TENANT_ID))
                    .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

            client.postDormancyStatus(instruction);
            mockServer.verify();
        }

        @Test
        void postDormancyStatus_wrapsRestClientResponseExceptionAsSynapsePostingException() {
            SynapseDormancyStatusInstruction instruction = buildInstruction(TRACE_ID);

            mockServer.expect(requestTo(FULL_DORMANCY_URL)).andExpect(method(HttpMethod.POST))
                    .andRespond(withBadGateway().body("upstream"));

            assertThatThrownBy(() -> client.postDormancyStatus(instruction)).isInstanceOf(SynapsePostingException.class)
                    .hasMessageContaining("traceId=" + TRACE_ID).hasMessageContaining("502")
                    .hasCauseInstanceOf(RestClientResponseException.class);
            mockServer.verify();
        }

        @Test
        void postDormancyStatus_wrapsResourceAccessExceptionAsSynapsePostingException() {
            SynapseDormancyStatusInstruction instruction = buildInstruction(TRACE_ID);

            mockServer.expect(requestTo(FULL_DORMANCY_URL)).andExpect(method(HttpMethod.POST)).andRespond(req -> {
                throw new IOException("connection refused");
            });

            assertThatThrownBy(() -> client.postDormancyStatus(instruction)).isInstanceOf(SynapsePostingException.class)
                    .hasMessageContaining("traceId=" + TRACE_ID).hasMessageContaining("connection error")
                    .hasCauseInstanceOf(ResourceAccessException.class);
            mockServer.verify();
        }

        private SynapseDormancyStatusInstruction buildInstruction(String traceId) {
            return SynapseDormancyStatusInstruction.builder().traceId(traceId).savingsAccountId(4242L).clientId(77L).officeId(7L)
                    .previousSubStatus(SavingsAccountSubStatusEnum.NONE).targetSubStatus(SavingsAccountSubStatusEnum.INACTIVE)
                    .effectiveDate(LocalDate.of(2026, 4, 30)).transitionReason("Inactive 90 days").currencyCode("NGN").build();
        }
    }

    private static SynapseInterestPostingBatch buildBatch(String batchId) {
        SynapseTransactionInstruction instruction = SynapseTransactionInstruction.builder().traceId("trace-1").savingsAccountId(100L)
                .officeId(10L).transactionType(SynapseTransactionInstruction.TransactionType.INTEREST_POSTING)
                .direction(SynapseTransactionInstruction.Direction.CREDIT).operation(SynapseTransactionInstruction.Operation.POST)
                .amount(new BigDecimal("250.00")).transactionDate(LocalDate.of(2026, 3, 20)).currencyCode("NGN").batchId(batchId).build();

        return SynapseInterestPostingBatch.builder().batchId(batchId).postingDate(LocalDate.of(2026, 3, 20)).totalCount(1)
                .transactions(List.of(instruction)).build();
    }

    @Nested
    class EodCalls {

        @Test
        void pushesTheBusinessDate_andReadsBackWhatSynapseStamps() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/business-date")).andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token")).andExpect(header(TENANT_HEADER, TENANT_ID))
                    .andExpect(jsonPath("$.date").value("2026-10-07"))
                    .andRespond(withSuccess(
                            "{\"businessDate\":\"2026-10-07\",\"fineractBusinessDate\":\"2026-10-07\",\"requestedDate\":\"2026-10-07\"}",
                            MediaType.APPLICATION_JSON));

            SynapseBusinessDateResponse response = client.postBusinessDate(LocalDate.of(2026, 10, 7));

            assertEquals(LocalDate.of(2026, 10, 7), response.getBusinessDate());
            mockServer.verify();
        }

        @Test
        void aRefusedPushSurfacesSynapsesReason() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/business-date"))
                    .andRespond(withStatus(HttpStatus.CONFLICT).body("Business date 2026-10-08 is ahead of Fineract's 2026-10-07"));

            SynapsePostingException thrown = assertThrows(SynapsePostingException.class,
                    () -> client.postBusinessDate(LocalDate.of(2026, 10, 8)));

            assertTrue(thrown.getMessage().contains("409"));
            assertTrue(thrown.getMessage().contains("ahead of Fineract"));
        }

        @Test
        void readsTheReplayStatusForADay() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/replay-status?businessDate=2026-10-06"))
                    .andExpect(method(HttpMethod.GET)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                    .andRespond(withSuccess("{\"businessDate\":\"2026-10-06\",\"cutoff\":null,\"cutoffSource\":\"NONE\","
                            + "\"inFlight\":{\"pendingTx\":1,\"postedToTbOnly\":0,\"inboxPending\":2,\"inboxRetrying\":0,\"total\":3},"
                            + "\"failedNewCount\":1,\"failedLegacyCount\":0,"
                            + "\"failedNew\":[{\"reference\":\"REF-1\",\"source\":\"TX\",\"type\":\"DEPOSIT\",\"status\":\"UNKNOWN\",\"transactionDate\":\"2026-10-06\",\"error\":null}],"
                            + "\"failedLegacy\":[],\"flexcubeOutboxPending\":0,\"interestRows\":{\"INTEREST_POSTING\":{\"POSTED_TO_FINERACT\":4}},\"drained\":false}",
                            MediaType.APPLICATION_JSON));

            SynapseReplayStatus status = client.getReplayStatus(LocalDate.of(2026, 10, 6));

            assertEquals(3, status.getInFlight().getTotal());
            assertEquals(1, status.getFailedNewCount());
            assertEquals("REF-1", status.getFailedNew().get(0).getReference());
            assertEquals(4L, status.getInterestRows().get("INTEREST_POSTING").get("POSTED_TO_FINERACT"));
            assertEquals(false, status.isDrained());
        }

        @Test
        void readsTheSettlementStatusForADay() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/settlement-status?businessDate=2026-10-06"))
                    .andExpect(method(HttpMethod.GET)).andRespond(
                            withSuccess("{\"businessDate\":\"2026-10-06\",\"rail\":\"BILLS\",\"byStatus\":{\"COMPLETED\":2},\"inFlight\":0,"
                                    + "\"cutoff\":\"2026-10-05\",\"cutoffSource\":\"LAST_COMPLETED_RUN\",\"failedNewCount\":0,"
                                    + "\"failedLegacyCount\":1,\"failedNew\":[],\"failedLegacy\":[{\"batchId\":\"b-old\",\"status\":\"FAILED\","
                                    + "\"loadedDate\":\"2026-10-02\",\"lastError\":\"bad file\"}],"
                                    + "\"awaitingEoc\":[\"b-1\"],\"loadedForDate\":2,\"settled\":false}", MediaType.APPLICATION_JSON));

            SynapseSettlementStatus status = client.getSettlementStatus(LocalDate.of(2026, 10, 6));

            assertEquals(List.of("b-1"), status.getAwaitingEoc());
            assertEquals(2, status.getLoadedForDate());
            assertEquals(LocalDate.of(2026, 10, 5), status.getCutoff());
            assertEquals("LAST_COMPLETED_RUN", status.getCutoffSource());
            assertEquals(0, status.getFailedNewCount());
            assertEquals(1, status.getFailedLegacyCount());
            assertEquals("b-old", status.getFailedLegacy().get(0).getBatchId());
            assertEquals(LocalDate.of(2026, 10, 2), status.getFailedLegacy().get(0).getLoadedDate());
            assertEquals(false, status.isSettled());
        }

        @Test
        void startsAMirrorRun_andReadsTheRunAndItsLegs() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/mirror-runs")).andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token")).andExpect(header(TENANT_HEADER, TENANT_ID))
                    .andExpect(jsonPath("$.businessDate").value("2026-10-13")).andExpect(jsonPath("$.job").value("DEPOSIT"))
                    .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(MIRROR_RUN));

            SynapseMirrorRun view = client.startMirrorRun(LocalDate.of(2026, 10, 13), "DEPOSIT");

            assertEquals("PLANNING", view.getRun().getStatus());
            assertEquals(LocalDate.of(2026, 10, 13), view.getRun().getBusinessDate());
            assertEquals(1, view.getLegs().size());
            assertEquals("CUSTOMER_REGULAR_SAVINGS_DEPOSIT", view.getLegs().get(0).getLogicalName());
            assertEquals(new BigDecimal("123.45"), view.getLegs().get(0).getAmount());
            assertEquals(55L, view.getLegs().get(0).getFinBridgeGlId());
            assertEquals("FLX-SRC", view.getLegs().get(0).getFlexSource().getGl());
            assertEquals(null, view.getLegs().get(0).getFlexBridge());
            assertEquals("DRIFT", view.getLegs().get(0).getDriftStatus());
            mockServer.verify();
        }

        @Test
        void aRefusedMirrorRunKeepsSynapsesReasonAndTheCause() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/mirror-runs")).andRespond(withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"mirroring is disabled\"}"));

            SynapsePostingException thrown = assertThrows(SynapsePostingException.class,
                    () -> client.startMirrorRun(LocalDate.of(2026, 10, 13), "DEPOSIT"));

            assertTrue(thrown.getMessage().contains("409"));
            assertTrue(thrown.getMessage().contains("mirroring is disabled"));
            assertTrue(thrown.getCause() instanceof RestClientResponseException);
        }

        @Test
        void readsAMirrorRunByDateAndJob() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/mirror-runs/2026-10-13/TAX")).andExpect(method(HttpMethod.GET))
                    .andExpect(header(TENANT_HEADER, TENANT_ID)).andRespond(withSuccess(MIRROR_RUN, MediaType.APPLICATION_JSON));

            assertEquals(1L, client.getMirrorRun(LocalDate.of(2026, 10, 13), "TAX").getRun().getId());
            mockServer.verify();
        }

        @Test
        void runsAndReadsTheZeroCheck() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/zero-checks")).andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.businessDate").value("2026-10-13"))
                    .andRespond(withSuccess(ZERO_CHECK, MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/zero-checks/2026-10-13")).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(ZERO_CHECK, MediaType.APPLICATION_JSON));

            SynapseZeroCheck ran = client.runZeroCheck(LocalDate.of(2026, 10, 13));
            SynapseZeroCheck read = client.getZeroCheck(LocalDate.of(2026, 10, 13));

            assertEquals("FAILED", ran.getRun().getStatus());
            assertEquals(2, ran.getChecks().size());
            assertEquals("SETTLEMENT_BRIDGE", ran.getChecks().get(1).getKind());
            assertEquals(new BigDecimal("0.00"), ran.getChecks().get(0).getExpected());
            assertEquals(null, ran.getChecks().get(1).getActual());
            assertEquals("UNVERIFIED", read.getChecks().get(1).getResult());
            mockServer.verify();
        }

        @Test
        void aZeroCheckOutageIsAPostingExceptionWithTheCause() {
            mockServer.expect(requestTo(BASE_URL + "/api/v1/proxy/eod/zero-checks")).andRespond(withServerError());

            SynapsePostingException thrown = assertThrows(SynapsePostingException.class,
                    () -> client.runZeroCheck(LocalDate.of(2026, 10, 13)));

            assertTrue(thrown.getMessage().contains("500"));
            assertTrue(thrown.getCause() instanceof RestClientResponseException);
        }
    }

    private static final String MIRROR_RUN = "{\"run\":{\"id\":1,\"businessDate\":\"2026-10-13\",\"job\":\"DEPOSIT\",\"status\":\"PLANNING\","
            + "\"attempts\":0,\"lastError\":null,\"acceptUnresolvedCommission\":false},\"legs\":[{\"runId\":1,\"rowNo\":1,\"mappingId\":3,"
            + "\"logicalName\":\"CUSTOMER_REGULAR_SAVINGS_DEPOSIT\",\"finSourceGlId\":10,\"finSourceGlCode\":\"2001\",\"finBridgeGlId\":55,"
            + "\"normalSide\":\"CREDIT\",\"flexSource\":{\"gl\":\"FLX-SRC\",\"branch\":\"001\"},\"flexBridge\":null,\"amount\":123.45,"
            + "\"lateAmount\":0,\"windowFrom\":null,\"windowTo\":\"2026-10-13\",\"flipped\":false,\"reference\":\"EOD-2026-10-13-DEPOSIT-1\","
            + "\"finStatus\":\"PENDING\",\"finTxId\":null,\"flexStatus\":\"PENDING\",\"driftStatus\":\"DRIFT\","
            + "\"fineractMovement\":{\"debits\":1},\"note\":null,\"lastError\":null,\"somethingNew\":true}]}";

    private static final String ZERO_CHECK = "{\"run\":{\"businessDate\":\"2026-10-13\",\"status\":\"FAILED\",\"attempts\":1},\"checks\":["
            + "{\"checkNo\":1,\"kind\":\"EOD_PAIR\",\"name\":\"DEPOSIT bridge\",\"side\":\"FINERACT\",\"glCode\":\"2901\",\"branch\":null,"
            + "\"expected\":0.00,\"actual\":0.00,\"result\":\"CLEAR\",\"note\":null},"
            + "{\"checkNo\":2,\"kind\":\"SETTLEMENT_BRIDGE\",\"name\":\"Inflow bridge\",\"side\":\"FLEXCUBE\",\"glCode\":\"FLX-IN\","
            + "\"branch\":\"001\",\"expected\":0.00,\"actual\":null,\"result\":\"UNVERIFIED\",\"note\":\"Flexcube unavailable\"}]}";
}
