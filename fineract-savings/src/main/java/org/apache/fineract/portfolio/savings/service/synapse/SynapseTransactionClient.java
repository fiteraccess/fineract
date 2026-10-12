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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBusinessDateRequest;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBusinessDateResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseReplayStatus;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseSettlementStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class SynapseTransactionClient {

    private static final String BATCH_ENDPOINT_PATH = "/api/v1/proxy/savings/interest-postings:batch";
    private static final String DORMANCY_ENDPOINT_PATH = "/api/v1/proxy/savings/dormancy-statuses";
    private static final String MONTHLY_STATEMENT_PLAN_PATH = "/api/v1/proxy/statements/monthly:plan";
    private static final String EOD_BUSINESS_DATE_PATH = "/api/v1/proxy/eod/business-date";
    private static final String EOD_REPLAY_STATUS_PATH = "/api/v1/proxy/eod/replay-status";
    private static final String EOD_SETTLEMENT_STATUS_PATH = "/api/v1/proxy/eod/settlement-status";
    private static final String TENANT_HEADER = "Fineract-Platform-TenantId";

    private final RestTemplate restTemplate;
    private final String postUrl;
    private final String dormancyUrl;
    private final String monthlyStatementPlanUrl;
    private final String eodBusinessDateUrl;
    private final String eodReplayStatusUrl;
    private final String eodSettlementStatusUrl;
    private final String apiKey;

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW")
    public SynapseTransactionClient(RestTemplate restTemplate, String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "fineract.synapse.api-key (FINERACT_SYNAPSE_API_KEY) must be set when fineract.synapse.enabled=true");
        }
        this.restTemplate = restTemplate;
        this.postUrl = baseUrl + BATCH_ENDPOINT_PATH;
        this.dormancyUrl = baseUrl + DORMANCY_ENDPOINT_PATH;
        this.monthlyStatementPlanUrl = baseUrl + MONTHLY_STATEMENT_PLAN_PATH;
        this.eodBusinessDateUrl = baseUrl + EOD_BUSINESS_DATE_PATH;
        this.eodReplayStatusUrl = baseUrl + EOD_REPLAY_STATUS_PATH;
        this.eodSettlementStatusUrl = baseUrl + EOD_SETTLEMENT_STATUS_PATH;
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

    /**
     * Asks Synapse to enqueue the previous month's statement run (AB-358, R-D-24).
     *
     * <p>
     * Sends no period: Synapse derives the month itself so the two services cannot disagree about which month "now"
     * belongs to — this fires at 02:00 on the 1st, close enough to a boundary for two independently-read clocks to land
     * in different months.
     *
     * <p>
     * Returns once the rows exist, well inside the read timeout. Delivery happens afterwards in Synapse's own worker,
     * so a success here means <em>enqueued</em>.
     */
    public SynapseMonthlyStatementPlanResponse postMonthlyStatementPlan() {
        log.debug("Requesting monthly statement plan from {}", monthlyStatementPlanUrl);
        SynapseMonthlyStatementPlanResponse body;
        try {
            body = post(monthlyStatementPlanUrl, null, SynapseMonthlyStatementPlanResponse.class);
        } catch (RestClientResponseException e) {
            throw new SynapsePostingException(
                    "Synapse monthly statement planning failed with HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SynapsePostingException("Synapse monthly statement planning failed: connection error to " + monthlyStatementPlanUrl,
                    e);
        }
        return body;
    }

    /**
     * The EOD rollover's push: tells every Synapse pod the business date Fineract has just advanced to. Synapse refuses
     * a date ahead of its own reading of Fineract (409), which surfaces here as a posting exception.
     */
    public SynapseBusinessDateResponse postBusinessDate(LocalDate businessDate) {
        log.debug("Pushing business date {} to {}", businessDate, eodBusinessDateUrl);
        try {
            return post(eodBusinessDateUrl, new SynapseBusinessDateRequest(businessDate.toString()), SynapseBusinessDateResponse.class);
        } catch (RestClientResponseException e) {
            throw new SynapsePostingException("Synapse business-date push failed with HTTP " + e.getStatusCode() + " date=" + businessDate
                    + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SynapsePostingException("Synapse business-date push failed: connection error to " + eodBusinessDateUrl, e);
        }
    }

    /** The replay-drain gate's poll: what dated on or before {@code businessDate} has not reached Fineract. */
    public SynapseReplayStatus getReplayStatus(LocalDate businessDate) {
        return get(eodReplayStatusUrl + "?businessDate=" + businessDate, SynapseReplayStatus.class, "replay status");
    }

    /** The Bills gate's poll: the BILLS settlement batches loaded on or before {@code businessDate}. */
    public SynapseSettlementStatus getSettlementStatus(LocalDate businessDate) {
        return get(eodSettlementStatusUrl + "?businessDate=" + businessDate, SynapseSettlementStatus.class, "settlement status");
    }

    private <ResT> ResT get(String url, Class<ResT> responseType, String what) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        headers.set(TENANT_HEADER, tenantIdentifier());
        try {
            ResponseEntity<ResT> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType);
            return response.getBody();
        } catch (RestClientResponseException e) {
            throw new SynapsePostingException(
                    "Synapse " + what + " read failed with HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SynapsePostingException("Synapse " + what + " read failed: connection error to " + url, e);
        }
    }

    private <ReqT, ResT> ResT post(String url, ReqT body, Class<ResT> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, apiKey);
        headers.set(TENANT_HEADER, tenantIdentifier());
        HttpEntity<ReqT> request = new HttpEntity<>(body, headers);
        ResponseEntity<ResT> response = restTemplate.postForEntity(url, request, responseType);
        return response.getBody();
    }

    /**
     * Synapse rejects any request without a tenant indication, so dispatching without a tenant context would only
     * produce an opaque 400 downstream; fail here with the actual cause instead. The outbox executor propagates the
     * scheduler's tenant context via {@code ContextAwareTaskDecorator}.
     */
    private static String tenantIdentifier() {
        FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        if (tenant == null) {
            throw new SynapsePostingException("No tenant context available for Synapse dispatch");
        }
        return tenant.getTenantIdentifier();
    }
}
