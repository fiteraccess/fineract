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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.time.LocalDate;
import java.util.HashMap;
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
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.support.TenantJdbcSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.JdbcTemplate;

public class SynapseChargePostingIntegrationTest {

    private static final String DATE = "01 January 2023";
    private static final String BATCH_URL = "/api/v1/proxy/savings/interest-postings:batch";

    @RegisterExtension
    static WireMockExtension synapse = WireMockExtension.newInstance().options(wireMockConfig().port(18089)).build();

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private GlobalConfigurationHelper globalConfigHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        globalConfigHelper = new GlobalConfigurationHelper();

        tenantJdbc().update("DELETE FROM synapse_outbox WHERE status IN ('PENDING','FAILED')");

        synapse.stubFor(WireMock.post(WireMock.urlEqualTo(BATCH_URL))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"batchId\":\"stub\",\"accepted\":999,\"failed\":0,\"results\":[]}")));
    }

    @AfterEach
    public void teardown() {
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(false));
    }

    @Nested
    class WhenSynapseEnabled {

        @Test
        void routesChargeToOutboxAndBalanceUnchanged() {
            Integer savingsId = createSavingsWithChargeInBusinessDateContext(true);

            payDueCharges();

            String payload = latestOutboxPayload(savingsId);
            assertTrue(payload.contains("\"savingsAccountId\":" + savingsId));
            assertTrue(payload.contains("\"transactionType\":\"SAVINGS_CHARGE\""));
            assertTrue(payload.contains("\"direction\":\"DEBIT\""));
            assertTrue(payload.contains("\"savingsAccountChargeId\""));

            assertEquals(1000.0f, balanceOf(savingsId), 0.01f,
                    "Balance should remain 1000 — charge routed to outbox, not applied directly");

            new SchedulerJobHelper(requestSpec).executeAndAwaitJob("Dispatch Synapse Outbox");
            synapse.verify(postRequestedFor(urlEqualTo(BATCH_URL)));
        }

        @Test
        void outboxEntryContainsBatchAndTraceIds() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDepositAndCharge(gl, true);

            payDueCharges();

            Map<String, Object> row = tenantJdbc().queryForMap("SELECT batch_id, trace_id FROM synapse_outbox "
                    + "WHERE task_type = 'CHARGE_POSTING' AND account_id = ? ORDER BY id DESC LIMIT 1", savingsId.longValue());

            assertNotNull(row.get("batch_id"), "Outbox entry should have a batch_id");
            assertNotNull(row.get("trace_id"), "Outbox entry should have a trace_id");
        }

        @Test
        void outboxPayloadContainsChargeMetadata() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDepositAndCharge(gl, true);

            payDueCharges();

            String payload = latestOutboxPayload(savingsId);
            assertTrue(payload.contains("\"amount\""));
            assertTrue(payload.contains("\"operation\":\"POST\""));
            assertTrue(payload.contains("\"savingsAccountChargeId\""));
        }
    }

    @Nested
    class WhenSynapseDisabled {

        @Test
        void appliesChargeDirectlyAndNoOutboxEntry() {
            Integer savingsId = createSavingsWithChargeInBusinessDateContext(false);

            payDueCharges();

            List<Map<String, Object>> rows = tenantJdbc().queryForList(
                    "SELECT id FROM synapse_outbox WHERE task_type = 'CHARGE_POSTING' AND account_id = ?", savingsId.longValue());
            assertTrue(rows.isEmpty(), "No CHARGE_POSTING outbox entry expected when Synapse is disabled");

            assertEquals(900.0f, balanceOf(savingsId), 0.01f, "Balance should be 900 (1000 - 100 charge) when applied directly");
        }
    }

    @Nested
    class ReplayChargePosting {

        @SuppressWarnings("unchecked")
        @Test
        void appliesChargeAndDecreasesBalance() {
            Account[] gl = createCashBasedGlAccounts();
            int[] ids = createActiveSavingsWithDepositAndChargeReturningIds(gl);
            int savingsId = ids[0];
            int savingsAccountChargeId = ids[1];

            Integer txnId = replayCharge(savingsId, "100.00", (long) savingsAccountChargeId, null);

            assertEquals(900.0f, balanceOf(savingsId), 0.01f);
            assertTrue((Boolean) transactionType(savingsId, txnId).get("feeDeduction"));
        }
    }

    @Nested
    class ReplayIdempotency {

        @Test
        void sameTraceIdReturnsSameTransactionAndDoesNotDoublePost() {
            Account[] gl = createCashBasedGlAccounts();
            int[] ids = createActiveSavingsWithDepositAndChargeReturningIds(gl);
            int savingsId = ids[0];
            int savingsAccountChargeId = ids[1];
            String traceId = UUID.randomUUID().toString();

            Integer firstTxnId = replayCharge(savingsId, "100.00", (long) savingsAccountChargeId, traceId);
            Integer secondTxnId = replayCharge(savingsId, "100.00", (long) savingsAccountChargeId, traceId);

            assertEquals(firstTxnId, secondTxnId);
            assertEquals(900.0f, balanceOf(savingsId), 0.01f);
        }
    }

    // -- helpers ----------------------------------------------------------------

    private Integer replayCharge(Integer savingsId, String amount, Long savingsAccountChargeId, String traceId) {
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        String json = SavingsAccountHelper.buildReplayChargePostingJson(amount, DATE, savingsAccountChargeId, traceId);
        return new SavingsAccountHelper(requestSpec, responseSpec).replayChargePosting(savingsId, json);
    }

    @SuppressWarnings("unchecked")
    private HashMap transactionType(Integer savingsId, Integer txnId) {
        HashMap txn = new SavingsAccountHelper(requestSpec, responseSpec).getTransactionDetails(savingsId, txnId);
        return (HashMap) txn.get("transactionType");
    }

    private int[] createActiveSavingsWithDepositAndChargeReturningIds(Account[] gl) {
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(true));
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                new PutGlobalConfigurationsRequest().enabled(true));
        BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, LocalDate.of(2023, 1, 2));

        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, DATE);
        Integer productId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                        .withInterestCalculationPeriodTypeAsDailyBalance().withAccountingRuleAsCashBased(gl).build(),
                requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", DATE);
        sh.approveSavingsOnDate(savingsId, DATE);
        sh.activateSavingsAccount(savingsId, DATE);
        sh.depositToSavingsAccount(savingsId, "1000", DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        Integer chargeId = ChargesHelper.createCharges(requestSpec, responseSpec, ChargesHelper.getSavingsSpecifiedDueDateJSON());
        Integer savingsAccountChargeId = sh.addChargesForSavingsWithDueDate(savingsId, chargeId, DATE, 100);
        synapse.resetRequests();
        return new int[] { savingsId, savingsAccountChargeId };
    }

    private Integer createSavingsWithChargeInBusinessDateContext(boolean enableSynapse) {
        try {
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(enableSynapse));
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));

            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, LocalDate.of(2023, 1, 2));

            Account[] gl = createCashBasedGlAccounts();
            return createActiveSavingsWithDepositAndCharge(gl, false);
        } catch (RuntimeException e) {
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
            throw e;
        }
    }

    private Integer createActiveSavingsWithDepositAndCharge(Account[] gl, boolean needBusinessDate) {
        if (needBusinessDate) {
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(true));
            globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, LocalDate.of(2023, 1, 2));
        }

        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, DATE);
        Integer productId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                        .withInterestCalculationPeriodTypeAsDailyBalance().withAccountingRuleAsCashBased(gl).build(),
                requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", DATE);
        sh.approveSavingsOnDate(savingsId, DATE);
        sh.activateSavingsAccount(savingsId, DATE);
        sh.depositToSavingsAccount(savingsId, "1000", DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        Integer chargeId = ChargesHelper.createCharges(requestSpec, responseSpec, ChargesHelper.getSavingsSpecifiedDueDateJSON());
        sh.addChargesForSavingsWithDueDate(savingsId, chargeId, DATE, 100);
        synapse.resetRequests();
        return savingsId;
    }

    private void payDueCharges() {
        new SchedulerJobHelper(requestSpec).executeAndAwaitJob("Pay Due Savings Charges");
    }

    private String latestOutboxPayload(Integer savingsId) {
        List<Map<String, Object>> rows = tenantJdbc().queryForList(
                "SELECT payload FROM synapse_outbox WHERE task_type = 'CHARGE_POSTING' AND account_id = ? ORDER BY id DESC LIMIT 1",
                savingsId.longValue());
        assertTrue(!rows.isEmpty(), "Expected at least one CHARGE_POSTING outbox entry");
        return rows.get(0).get("payload").toString();
    }

    @SuppressWarnings("unchecked")
    private float balanceOf(Integer savingsId) {
        return (Float) ((HashMap) new SavingsAccountHelper(requestSpec, responseSpec).getSavingsSummary(savingsId)).get("accountBalance");
    }

    private Account[] createCashBasedGlAccounts() {
        AccountHelper ah = new AccountHelper(requestSpec, responseSpec);
        return new Account[] { ah.createAssetAccount(), ah.createLiabilityAccount(), ah.createIncomeAccount(), ah.createExpenseAccount() };
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }
}
