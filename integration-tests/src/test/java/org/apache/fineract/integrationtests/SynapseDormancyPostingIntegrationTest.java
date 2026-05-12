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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.gson.Gson;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.JdbcTemplate;

public class SynapseDormancyPostingIntegrationTest {

    private static final String DORMANCY_URL = "/v1/proxy/savings/dormancy-statuses";
    private static final String DORMANCY_JOB = "Update Savings Dormant Accounts";
    private static final String DISPATCH_JOB = "Dispatch Synapse Outbox";
    private static final String CURRENCY = "USD";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern(CommonConstants.DATE_FORMAT, Locale.ENGLISH);
    private static final int PAST_DAYS = 100;
    private static final String DEPOSIT_AMOUNT = "1000";
    private static final float ESCHEAT_AMOUNT = 1000.00f;

    @RegisterExtension
    static WireMockExtension synapse = WireMockExtension.newInstance().options(wireMockConfig().port(18089)).build();

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private ResponseSpecification responseSpec400;
    private GlobalConfigurationHelper globalConfigHelper;
    private LocalDate businessDate;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        responseSpec400 = new ResponseSpecBuilder().expectStatusCode(400).build();
        globalConfigHelper = new GlobalConfigurationHelper();

        businessDate = Utils.getLocalDateOfTenant();
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                new PutGlobalConfigurationsRequest().enabled(true));
        BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, businessDate);

        tenantJdbc().update("DELETE FROM synapse_outbox");
        synapse.resetRequests();

        synapse.stubFor(WireMock.post(WireMock.urlEqualTo(DORMANCY_URL))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        "{\"traceId\":\"stub\",\"status\":\"ACCEPTED\",\"correlationId\":\"corr\",\"reason\":null,\"inflightCount\":null}")));
    }

    @AfterEach
    public void teardown() {
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(false));
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                new PutGlobalConfigurationsRequest().enabled(false));
    }

    @Nested
    class WhenSynapseEnabled {

        @Test
        void inactiveTransitionRoutedToOutbox() {
            Integer savingsId = createBackdatedDormancyEnabledSavings(0);
            int baselineJournalEntries = journalEntryCountForAccount(savingsId);

            triggerDormancyDetection();

            Map<String, Object> row = latestDormancyOutboxRow(savingsId);
            assertOutboxPayloadShape(row, savingsId, "INACTIVE", "NONE");
            assertEquals(0, subStatusOf(savingsId), "sub-status should remain NONE until callback applies INACTIVE");
            assertEquals(baselineJournalEntries, journalEntryCountForAccount(savingsId),
                    "dormancy detection must not post journal entries");
        }

        @Test
        void dormantTransitionRoutedToOutbox() {
            Integer savingsId = createBackdatedDormancyEnabledSavings(100);
            int baselineJournalEntries = journalEntryCountForAccount(savingsId);

            triggerDormancyDetection();

            Map<String, Object> row = latestDormancyOutboxRow(savingsId);
            assertOutboxPayloadShape(row, savingsId, "DORMANT", "INACTIVE");
            assertEquals(100, subStatusOf(savingsId), "sub-status should remain INACTIVE until callback applies DORMANT");
            assertEquals(baselineJournalEntries, journalEntryCountForAccount(savingsId),
                    "dormancy detection must not post journal entries");
        }

        @Test
        void escheatTransitionRoutedToOutbox() {
            Integer savingsId = createBackdatedDormancyEnabledSavings(200);
            int baselineJournalEntries = journalEntryCountForAccount(savingsId);

            triggerDormancyDetection();

            Map<String, Object> row = latestDormancyOutboxRow(savingsId);
            assertOutboxPayloadShape(row, savingsId, "ESCHEAT", "DORMANT");
            assertEquals(200, subStatusOf(savingsId), "sub-status should remain DORMANT until callback applies ESCHEAT");
            assertEquals(300, statusOf(savingsId), "account should remain ACTIVE; closure happens on callback");
            assertEquals(baselineJournalEntries, journalEntryCountForAccount(savingsId),
                    "dormancy detection must not post journal entries");
        }
    }

    @Nested
    class OutboxDispatchToSynapse {

        @Test
        @SuppressWarnings("unchecked")
        void dispatcherPostsLockedNineFieldsToSynapseEndpoint() {
            Integer savingsId = createBackdatedDormancyEnabledSavings(0);
            triggerDormancyDetection();
            Map<String, Object> outboxRow = latestDormancyOutboxRow(savingsId);

            dispatchOutbox();

            List<LoggedRequest> sent = synapse.findAll(postRequestedFor(urlEqualTo(DORMANCY_URL)));
            List<Map<String, Object>> bodiesForAccount = sent.stream()
                    .map(r -> (Map<String, Object>) new Gson().fromJson(r.getBodyAsString(), Map.class))
                    .filter(b -> ((Number) b.get("savingsAccountId")).longValue() == savingsId.longValue()).toList();
            assertEquals(1, bodiesForAccount.size(), "dispatcher should POST exactly one dormancy instruction for the test account");

            Map<String, Object> body = bodiesForAccount.get(0);
            assertEquals(savingsId.longValue(), ((Number) body.get("savingsAccountId")).longValue());
            assertEquals(outboxRow.get("trace_id"), body.get("traceId"));
            assertEquals("INACTIVE", body.get("targetSubStatus"));
            assertEquals("NONE", body.get("previousSubStatus"));
            assertEquals(CURRENCY, body.get("currencyCode"));
            assertNotNull(body.get("clientId"), "clientId");
            assertNotNull(body.get("officeId"), "officeId");
            assertNotNull(body.get("transitionReason"), "transitionReason");
            assertNotNull(parseEffectiveDate(body.get("effectiveDate")), "effectiveDate");

            String status = tenantJdbc().queryForObject("SELECT status FROM synapse_outbox WHERE id = ?", String.class,
                    ((Number) outboxRow.get("id")).longValue());
            assertEquals("SENT", status, "outbox row should be marked SENT after successful dispatch");
        }
    }

    @Nested
    class ReplayDormancyStatus {

        @Test
        void replayInactiveAppliesSubStatus() {
            Integer savingsId = createDepositedSavings();

            replayDormancy(savingsId, UUID.randomUUID().toString(), "INACTIVE", todayFmt(), null, CURRENCY);

            assertEquals(100, subStatusOf(savingsId));
            assertEquals(1, depositOrWithdrawalCountOf(savingsId), "no new transaction expected");
        }

        @Test
        void replayDormantAppliesSubStatus() {
            Integer savingsId = createDepositedSavings();
            forceSubStatus(savingsId, 100);

            replayDormancy(savingsId, UUID.randomUUID().toString(), "DORMANT", todayFmt(), null, CURRENCY);

            assertEquals(200, subStatusOf(savingsId));
            assertEquals(1, depositOrWithdrawalCountOf(savingsId));
        }

        @Test
        void replayEscheatPostsTransactionAndJournalEntries() {
            Integer savingsId = createDepositedSavings();
            forceSubStatus(savingsId, 200);
            String traceId = UUID.randomUUID().toString();

            Integer escheatTxnId = replayDormancy(savingsId, traceId, "ESCHEAT", todayFmt(), formatAmount(ESCHEAT_AMOUNT), CURRENCY);

            assertEquals(600, statusOf(savingsId), "account status should be CLOSED");
            assertEquals(300, subStatusOf(savingsId), "sub-status should be ESCHEAT");
            assertEquals(businessDate, closedOnOf(savingsId), "closedon_date should be the effective date");

            Map<String, Object> txn = tenantJdbc().queryForMap(
                    "SELECT transaction_type_enum, amount, ref_no FROM m_savings_account_transaction WHERE id = ?",
                    escheatTxnId.longValue());
            assertEquals(19, ((Number) txn.get("transaction_type_enum")).intValue(), "transaction should be ESCHEAT (19)");
            assertEquals(ESCHEAT_AMOUNT, ((Number) txn.get("amount")).floatValue(), 0.01f);
            assertEquals(traceId, txn.get("ref_no"), "ref_no should equal trace id");

            assertEquals(2, journalEntryCountFor(escheatTxnId), "Dr SAVINGS_CONTROL + Cr ESCHEAT_LIABILITY = 2 entries");
        }
    }

    @Nested
    class ReplayIdempotency {

        @Test
        void sameTraceIdReplayedTwiceIsIdempotent() {
            Integer savingsId = createDepositedSavings();
            forceSubStatus(savingsId, 200);
            String traceId = UUID.randomUUID().toString();

            Integer first = replayDormancy(savingsId, traceId, "ESCHEAT", todayFmt(), formatAmount(ESCHEAT_AMOUNT), CURRENCY);
            Integer second = replayDormancy(savingsId, traceId, "ESCHEAT", todayFmt(), formatAmount(ESCHEAT_AMOUNT), CURRENCY);

            assertEquals(first, second, "second replay should return the same ESCHEAT transaction id");
            Integer escheatRows = tenantJdbc().queryForObject(
                    "SELECT COUNT(*) FROM m_savings_account_transaction WHERE savings_account_id = ? AND transaction_type_enum = 19",
                    Integer.class, savingsId.longValue());
            assertEquals(1, escheatRows, "exactly one ESCHEAT transaction expected");
            assertEquals(2, journalEntryCountFor(first), "exactly one Dr/Cr pair expected");
        }
    }

    @Nested
    class GuardRails {

        @Test
        void replayBackdatedEffectiveDateRejected() {
            Integer savingsId = createDepositedSavings();
            forceSubStatus(savingsId, 200);
            String backdated = businessDate.minusDays(1).format(DATE_FMT);

            String code = postReplayExpecting400(savingsId, SavingsAccountHelper.buildReplayDormancyStatusJson(UUID.randomUUID().toString(),
                    "ESCHEAT", backdated, formatAmount(ESCHEAT_AMOUNT), CURRENCY));

            assertEquals("error.msg.savings.escheat.backdated", code);
            assertEquals(200, subStatusOf(savingsId), "account state should remain DORMANT on rejection");
            assertEquals(300, statusOf(savingsId));
        }

        @Test
        void replayAmountMismatchRejected() {
            Integer savingsId = createDepositedSavings();
            forceSubStatus(savingsId, 200);

            String code = postReplayExpecting400(savingsId, SavingsAccountHelper.buildReplayDormancyStatusJson(UUID.randomUUID().toString(),
                    "ESCHEAT", todayFmt(), formatAmount(999.00f), CURRENCY));

            assertEquals("error.msg.savings.escheat.amount.mismatch", code);
            assertEquals(200, subStatusOf(savingsId));
            assertEquals(300, statusOf(savingsId));
        }
    }

    // -- helpers ----------------------------------------------------------------

    private Integer createBackdatedDormancyEnabledSavings(int forcedSubStatus) {
        Integer savingsId = createDepositedSavings();
        backdateAccount(savingsId, PAST_DAYS);
        if (forcedSubStatus != 0) {
            forceSubStatus(savingsId, forcedSubStatus);
        }
        return savingsId;
    }

    private Integer createDepositedSavings() {
        enableSynapseFeatureFlag();

        Account[] gl = createCashBasedGlAccounts();
        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, todayFmt());
        String productJson = new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                .withInterestCalculationPeriodTypeAsDailyBalance().withDormancy().withAccountingRuleAsCashBased(gl).build();
        productJson = withEscheatLiabilityId(productJson, liabilityIdOf(gl));
        Integer productId = SavingsProductHelper.createSavingsProduct(productJson, requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", todayFmt());
        sh.approveSavingsOnDate(savingsId, todayFmt());
        sh.activateSavingsAccount(savingsId, todayFmt());
        sh.depositToSavingsAccount(savingsId, DEPOSIT_AMOUNT, todayFmt(), CommonConstants.RESPONSE_RESOURCE_ID);
        synapse.resetRequests();
        return savingsId;
    }

    private void enableSynapseFeatureFlag() {
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(true));
    }

    private void backdateAccount(Integer savingsId, int daysAgo) {
        LocalDate target = businessDate.minusDays(daysAgo);
        tenantJdbc().update("UPDATE m_savings_account SET activatedon_date = ?, submittedon_date = ?, approvedon_date = ? WHERE id = ?",
                target, target, target, savingsId.longValue());
        tenantJdbc().update("UPDATE m_savings_account_transaction SET transaction_date = ?, submitted_on_date = ? "
                + "WHERE savings_account_id = ? AND transaction_type_enum IN (1,2)", target, target, savingsId.longValue());
    }

    private void forceSubStatus(Integer savingsId, int subStatus) {
        tenantJdbc().update("UPDATE m_savings_account SET sub_status_enum = ? WHERE id = ?", subStatus, savingsId.longValue());
    }

    private void triggerDormancyDetection() {
        new SchedulerJobHelper(requestSpec).executeAndAwaitJob(DORMANCY_JOB);
    }

    private void dispatchOutbox() {
        new SchedulerJobHelper(requestSpec).executeAndAwaitJob(DISPATCH_JOB);
    }

    private Integer replayDormancy(Integer savingsId, String traceId, String appliedSubStatus, String effectiveDate, String escheatAmount,
            String currencyCode) {
        String json = SavingsAccountHelper.buildReplayDormancyStatusJson(traceId, appliedSubStatus, effectiveDate, escheatAmount,
                currencyCode);
        return new SavingsAccountHelper(requestSpec, responseSpec).replayDormancyStatus(savingsId, json);
    }

    @SuppressWarnings("unchecked")
    private String postReplayExpecting400(Integer savingsId, String jsonBody) {
        String url = "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions?command=replayDormancyStatus&"
                + Utils.TENANT_IDENTIFIER;
        List<Map<String, Object>> errors = (List<Map<String, Object>>) Utils.performServerPost(requestSpec, responseSpec400, url, jsonBody,
                CommonConstants.RESPONSE_ERROR);
        assertNotNull(errors, "expected validation errors");
        assertTrue(!errors.isEmpty(), "expected at least one validation error");
        return (String) errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE);
    }

    private Map<String, Object> latestDormancyOutboxRow(Integer savingsId) {
        List<Map<String, Object>> rows = tenantJdbc().queryForList("SELECT id, trace_id, batch_id, payload, status FROM synapse_outbox "
                + "WHERE task_type = 'DORMANCY_STATUS' AND account_id = ? ORDER BY id DESC LIMIT 1", savingsId.longValue());
        assertTrue(!rows.isEmpty(), "expected one DORMANCY_STATUS outbox row for savings " + savingsId);
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private void assertOutboxPayloadShape(Map<String, Object> row, Integer savingsId, String target, String previous) {
        assertNotNull(row.get("trace_id"), "trace_id");
        assertNotNull(row.get("batch_id"), "batch_id");
        assertEquals("PENDING", row.get("status"), "newly written outbox row should be PENDING");

        Map<String, Object> payload = new Gson().fromJson(row.get("payload").toString(), Map.class);
        assertEquals(savingsId.longValue(), ((Number) payload.get("savingsAccountId")).longValue());
        assertEquals(target, payload.get("targetSubStatus"));
        assertEquals(previous, payload.get("previousSubStatus"));
        assertEquals(CURRENCY, payload.get("currencyCode"));
        assertEquals(row.get("trace_id"), payload.get("traceId"));
        assertNotNull(payload.get("clientId"), "clientId");
        assertNotNull(payload.get("officeId"), "officeId");
        assertNotNull(payload.get("transitionReason"), "transitionReason");
        assertNotNull(parseEffectiveDate(payload.get("effectiveDate")), "effectiveDate");
    }

    private LocalDate parseEffectiveDate(Object value) {
        if (value instanceof String s) {
            return LocalDate.parse(s);
        }
        if (value instanceof List<?> parts && parts.size() >= 3) {
            return LocalDate.of(((Number) parts.get(0)).intValue(), ((Number) parts.get(1)).intValue(), ((Number) parts.get(2)).intValue());
        }
        throw new IllegalArgumentException("Unexpected effectiveDate encoding: " + value);
    }

    private int subStatusOf(Integer savingsId) {
        Integer v = tenantJdbc().queryForObject("SELECT sub_status_enum FROM m_savings_account WHERE id = ?", Integer.class,
                savingsId.longValue());
        return v == null ? 0 : v;
    }

    private int statusOf(Integer savingsId) {
        Integer v = tenantJdbc().queryForObject("SELECT status_enum FROM m_savings_account WHERE id = ?", Integer.class,
                savingsId.longValue());
        return v == null ? 0 : v;
    }

    private LocalDate closedOnOf(Integer savingsId) {
        return tenantJdbc().queryForObject("SELECT closedon_date FROM m_savings_account WHERE id = ?", LocalDate.class,
                savingsId.longValue());
    }

    private int depositOrWithdrawalCountOf(Integer savingsId) {
        Integer count = tenantJdbc().queryForObject(
                "SELECT COUNT(*) FROM m_savings_account_transaction " + "WHERE savings_account_id = ? AND transaction_type_enum IN (1,2)",
                Integer.class, savingsId.longValue());
        return count == null ? 0 : count;
    }

    private int journalEntryCountFor(Integer savingsTransactionId) {
        Integer count = tenantJdbc().queryForObject("SELECT COUNT(*) FROM acc_gl_journal_entry WHERE savings_transaction_id = ?",
                Integer.class, savingsTransactionId.longValue());
        return count == null ? 0 : count;
    }

    private int journalEntryCountForAccount(Integer savingsId) {
        Integer count = tenantJdbc().queryForObject("SELECT COUNT(*) FROM acc_gl_journal_entry je JOIN m_savings_account_transaction t "
                + "ON je.savings_transaction_id = t.id WHERE t.savings_account_id = ?", Integer.class, savingsId.longValue());
        return count == null ? 0 : count;
    }

    private String todayFmt() {
        return businessDate.format(DATE_FMT);
    }

    private String formatAmount(float amount) {
        return String.format(Locale.ENGLISH, "%.2f", amount);
    }

    private Account[] createCashBasedGlAccounts() {
        AccountHelper ah = new AccountHelper(requestSpec, responseSpec);
        return new Account[] { ah.createAssetAccount(), ah.createLiabilityAccount(), ah.createIncomeAccount(), ah.createExpenseAccount() };
    }

    private static Long liabilityIdOf(Account[] gl) {
        for (Account a : gl) {
            if (a.getAccountType() == Account.AccountType.LIABILITY) {
                return a.getAccountID().longValue();
            }
        }
        throw new IllegalStateException("No LIABILITY account in GL set");
    }

    @SuppressWarnings("unchecked")
    private static String withEscheatLiabilityId(String productJson, Long liabilityAccountId) {
        Map<String, Object> map = new Gson().fromJson(productJson, Map.class);
        map.put("escheatLiabilityId", liabilityAccountId.toString());
        return new Gson().toJson(map);
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }
}
