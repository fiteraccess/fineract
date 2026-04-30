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
package org.apache.fineract.integrationtests;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.BusinessDateHelper;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.GlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.SchedulerJobHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.support.TenantJdbcSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.JdbcTemplate;

public class SynapseOutboxOperationalIntegrationTest {

    private static final String BATCH_URL = "/api/v1/proxy/savings/interest-postings:batch";

    @RegisterExtension
    static WireMockExtension synapse = WireMockExtension.newInstance().options(wireMockConfig().port(18089)).build();

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }

    private Integer createOutboxRow() {
        stubSynapseOk();

        GlobalConfigurationHelper gcHelper = new GlobalConfigurationHelper();
        try {
            gcHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));

            String activationDate = "01 January 2022";
            LocalDate postingDate = LocalDate.of(2022, 2, 2);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, LocalDate.of(2022, 1, 1));

            Account[] gl = createCashBasedGlAccounts();
            Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, activationDate);
            Integer productId = SavingsProductHelper.createSavingsProduct(
                    new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                            .withInterestCalculationPeriodTypeAsDailyBalance().withAccountingRuleAsCashBased(gl).build(),
                    requestSpec, responseSpec);
            SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
            Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", activationDate);
            sh.approveSavingsOnDate(savingsId, activationDate);
            sh.activateSavingsAccount(savingsId, activationDate);
            sh.depositToSavingsAccount(savingsId, "1000", activationDate, CommonConstants.RESPONSE_RESOURCE_ID);

            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, postingDate);
            synapse.resetRequests();
            sh.postInterestForSavings(savingsId);
            synapse.verify(0, postRequestedFor(urlEqualTo(BATCH_URL)));

            return savingsId;
        } finally {
            gcHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
        }
    }

    private void stubSynapseOk() {
        synapse.stubFor(WireMock.post(WireMock.urlEqualTo(BATCH_URL))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"batchId\":\"stub\",\"accepted\":999,\"failed\":0,\"results\":[]}")));
    }

    private void stubSynapse500() {
        synapse.stubFor(WireMock.post(WireMock.urlEqualTo(BATCH_URL)).willReturn(WireMock.aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"Internal Server Error\"}")));
    }

    private Account[] createCashBasedGlAccounts() {
        AccountHelper ah = new AccountHelper(requestSpec, responseSpec);
        return new Account[] { ah.createAssetAccount(), ah.createLiabilityAccount(), ah.createIncomeAccount(), ah.createExpenseAccount() };
    }

    @Nested
    class DeadLetterTransitionAndManualRetry {

        @Test
        @Disabled
        void deadEntryCanBeRetriedViaApiAndThenDispatched() {
            // Create an outbox row via the normal savings interest-posting flow
            createOutboxRow();

            JdbcTemplate jdbc = tenantJdbc();

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, status, attempts, max_attempts FROM synapse_outbox WHERE status = 'PENDING' ORDER BY id DESC LIMIT 1");
            assertTrue(!rows.isEmpty(), "Expected at least one PENDING outbox row");
            Long outboxId = ((Number) rows.get(0).get("id")).longValue();
            int maxAttempts = ((Number) rows.get(0).get("max_attempts")).intValue();

            // Simulate exhausted retries: set status to DEAD and attempts to max
            // (The markFailed → DEAD transition is verified by unit tests; here we test the retry API end-to-end)
            jdbc.update("UPDATE synapse_outbox SET status = 'DEAD', attempts = ?, "
                    + "error_detail = 'simulated max retries exhausted' WHERE id = ?", maxAttempts, outboxId);

            String statusAfterDead = jdbc.queryForObject("SELECT status FROM synapse_outbox WHERE id = ?", String.class, outboxId);
            assertEquals("DEAD", statusAfterDead, "Entry should be DEAD after exhausting retries");

            // Test retry API resets the DEAD entry back to PENDING
            String retryUrl = "/fineract-provider/api/v1/synapse-outbox/" + outboxId + "/retry?" + Utils.TENANT_IDENTIFIER;
            Utils.performServerPost(requestSpec, responseSpec, retryUrl, "{}");

            String statusAfterRetry = jdbc.queryForObject("SELECT status FROM synapse_outbox WHERE id = ?", String.class, outboxId);
            assertEquals("PENDING", statusAfterRetry, "Entry should be PENDING after manual retry");

            int attemptsAfterRetry = jdbc.queryForObject("SELECT attempts FROM synapse_outbox WHERE id = ?", Integer.class, outboxId);
            assertEquals(0, attemptsAfterRetry, "Attempts should be reset to 0 after retry");

            // Neutralize any other PENDING entries so only our entry gets dispatched
            jdbc.update("UPDATE synapse_outbox SET status = 'SENT', completed_at = NOW() " + "WHERE status = 'PENDING' AND id != ?",
                    outboxId);

            // Dispatch should now succeed
            synapse.resetAll();
            stubSynapseOk();

            SchedulerJobHelper schedulerJobHelper = new SchedulerJobHelper(requestSpec);
            schedulerJobHelper.executeAndAwaitJob("Dispatch Synapse Outbox");

            String statusAfterDispatch = jdbc.queryForObject("SELECT status FROM synapse_outbox WHERE id = ?", String.class, outboxId);
            assertEquals("SENT", statusAfterDispatch, "Entry should be SENT after successful dispatch");
        }
    }

    @Nested
    class PurgeJob {

        @Test
        @Disabled
        void purgesOldSentEntries() {
            JdbcTemplate jdbc = tenantJdbc();

            String traceId = UUID.randomUUID().toString();
            Timestamp fortydaysAgo = Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS));
            jdbc.update("INSERT INTO synapse_outbox "
                    + "(trace_id, batch_id, task_type, account_id, payload, status, attempts, max_attempts, created_at, completed_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'SENT', 1, 20, ?, ?)", traceId, "purge-test-batch", "INTEREST_POSTING", 1L, "{}",
                    fortydaysAgo, fortydaysAgo);

            Integer countBefore = jdbc.queryForObject("SELECT COUNT(*) FROM synapse_outbox WHERE trace_id = ?", Integer.class, traceId);
            assertEquals(1, countBefore, "Row should exist before purge");

            SchedulerJobHelper schedulerJobHelper = new SchedulerJobHelper(requestSpec);
            schedulerJobHelper.executeAndAwaitJob("Purge Synapse Outbox");

            Integer countAfter = jdbc.queryForObject("SELECT COUNT(*) FROM synapse_outbox WHERE trace_id = ?", Integer.class, traceId);
            assertEquals(0, countAfter, "Row should be deleted after purge");
        }
    }
}
