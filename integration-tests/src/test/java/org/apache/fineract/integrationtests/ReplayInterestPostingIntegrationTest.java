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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import org.apache.fineract.integrationtests.common.TaxComponentHelper;
import org.apache.fineract.integrationtests.common.TaxGroupHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.JournalEntryHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsTestLifecycleExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;


//@ExtendWith({ SavingsTestLifecycleExtension.class })
public class ReplayInterestPostingIntegrationTest {

    private static final String DATE = "10 April 2022";

    @RegisterExtension
    static WireMockExtension synapse = WireMockExtension.newInstance()
            .options(wireMockConfig().port(18089))
            .build();

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
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(true));

        synapse.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/proxy/savings/interest-postings:batch"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"batchId\":\"stub\",\"accepted\":999,\"failed\":0,\"results\":[]}")));
    }

    @AfterEach
    public void teardown() {
        globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                new PutGlobalConfigurationsRequest().enabled(false));
    }

    @Nested
    class InterestPosting {

        @SuppressWarnings("unchecked")
        @Test
        void creditsAccountAndIncreasesBalance() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDeposit(gl, "1000");

            Integer txnId = replayPosting(savingsId, "50.00", "INTEREST_POSTING", null);

            assertEquals(1050.0f, balanceOf(savingsId), 0.01f);
            assertTrue((Boolean) transactionType(savingsId, txnId).get("interestPosting"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void createsExpenseDebitAndLiabilityCreditJournalEntries() {
            Account[] gl = createCashBasedGlAccounts();
            Account expenseAccount = gl[3];
            Account liabilityAccount = gl[1];
            Integer savingsId = createActiveSavingsWithDeposit(gl, "1000");

            Integer txnId = replayPosting(savingsId, "50.00", "INTEREST_POSTING", null);

            ArrayList<HashMap> entries = new JournalEntryHelper(requestSpec, responseSpec)
                    .getJournalEntriesByTransactionId("S" + txnId);
            boolean expenseDebited = false;
            boolean liabilityCredited = false;
            for (Map<String, Object> entry : entries) {
                String type = (String) ((HashMap) entry.get("entryType")).get("value");
                int accountId = ((Number) entry.get("glAccountId")).intValue();
                if ("DEBIT".equals(type) && accountId == expenseAccount.getAccountID()) {
                    expenseDebited = true;
                }
                if ("CREDIT".equals(type) && accountId == liabilityAccount.getAccountID()) {
                    liabilityCredited = true;
                }
            }
            assertTrue(expenseDebited, "Expected DEBIT to expense account");
            assertTrue(liabilityCredited, "Expected CREDIT to liability account");
        }
    }

    @Nested
    class Idempotency {

        @SuppressWarnings("unchecked")
        @Test
        void sameTraceIdReturnsSameTransactionAndDoesNotDoublePost() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDeposit(gl, "1000");
            String traceId = UUID.randomUUID().toString();

            Integer firstTxnId = replayPosting(savingsId, "50.00", "INTEREST_POSTING", traceId);
            Integer secondTxnId = replayPosting(savingsId, "50.00", "INTEREST_POSTING", traceId);

            assertEquals(firstTxnId, secondTxnId);
            assertEquals(1050.0f, balanceOf(savingsId), 0.01f);
        }
    }

    @Nested
    class OverdraftInterest {

        @SuppressWarnings("unchecked")
        @Test
        void debitsOverdrawnAccountAndDecreasesBalance() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createOverdrawnSavingsAccount(gl, "500", "1000");

            Integer txnId = replayPosting(savingsId, "25.00", "OVERDRAFT_INTEREST", null);

            assertEquals(-525.0f, balanceOf(savingsId), 0.01f);
            assertTrue((Boolean) transactionType(savingsId, txnId).get("overdraftInterest"));
            assertEquals(25.0f, transactionAmount(savingsId, txnId), 0.01f);
        }
    }

    @Nested
    class WithholdTax {

        @SuppressWarnings("unchecked")
        @Test
        void debitsAccountAndDecreasesBalance() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createSavingsWithWithholdTaxAndDeposit(gl, "1000");

            Integer txnId = replayPosting(savingsId, "30.00", "WITHHOLD_TAX", null);

            assertEquals(970.0f, balanceOf(savingsId), 0.01f);
            assertTrue((Boolean) transactionType(savingsId, txnId).get("withholdTax"));
            assertEquals(30.0f, transactionAmount(savingsId, txnId), 0.01f);
        }
    }

    @Nested
    class InvalidTransactionType {

        @Test
        void returnsServerError() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDeposit(gl, "1000");

            ResponseSpecification errorSpec = new ResponseSpecBuilder().expectStatusCode(500).build();
            SavingsAccountHelper errorHelper = new SavingsAccountHelper(requestSpec, errorSpec);
            String json = SavingsAccountHelper.buildReplayInterestPostingJson("50.00", DATE, "INVALID_TYPE",
                    UUID.randomUUID().toString(), null);
            errorHelper.replayInterestPosting(savingsId, json);
        }
    }

    @Nested
    class DirectPostingViaSynapse {

        private static final String BATCH_URL = "/api/v1/proxy/savings/interest-postings:batch";

        @Test
        void postInterestRoutedToSynapseWhenEnabled() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDeposit(gl, "1000");

            SavingsAccountHelper helper = new SavingsAccountHelper(requestSpec, responseSpec);
            helper.postInterestForSavings(savingsId);

            SchedulerJobHelper schedulerJobHelper = new SchedulerJobHelper(requestSpec);
            schedulerJobHelper.executeAndAwaitJob("Dispatch Synapse Outbox");

            synapse.verify(postRequestedFor(urlEqualTo(BATCH_URL)));
        }

        @Test
        void postInterestAsOnRoutedToSynapseWhenEnabled() {
            Account[] gl = createCashBasedGlAccounts();
            Integer savingsId = createActiveSavingsWithDeposit(gl, "1000");

            SavingsAccountHelper helper = new SavingsAccountHelper(requestSpec, responseSpec);
            helper.postInterestAsOnSavings(savingsId, DATE);

            SchedulerJobHelper schedulerJobHelper = new SchedulerJobHelper(requestSpec);
            schedulerJobHelper.executeAndAwaitJob("Dispatch Synapse Outbox");

            synapse.verify(postRequestedFor(urlEqualTo(BATCH_URL)));
        }
    }

    @Nested
    class OutboxDispatch {

        private static final String BATCH_URL = "/api/v1/proxy/savings/interest-postings:batch";

        @Test
        void postInterestCreatesOutboxRowAndDispatchJobMarksItSent() {
            GlobalConfigurationHelper globalConfigHelper = new GlobalConfigurationHelper();
            try {
                globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                        new PutGlobalConfigurationsRequest().enabled(true));

                String activationDate = "01 January 2022";
                LocalDate postingDate = LocalDate.of(2022, 2, 2);
                BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE,
                        LocalDate.of(2022, 1, 1));

                Account[] gl = createCashBasedGlAccounts();
                Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, activationDate);
                Integer productId = SavingsProductHelper.createSavingsProduct(
                        new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily()
                                .withInterestPostingPeriodTypeAsDaily()
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

                SchedulerJobHelper schedulerJobHelper = new SchedulerJobHelper(requestSpec);
                schedulerJobHelper.executeAndAwaitJob("Dispatch Synapse Outbox");

                List<LoggedRequest> requests = synapse.findAll(postRequestedFor(urlEqualTo(BATCH_URL)));
                assertFalse(requests.isEmpty(), "Expected at least one batch POST to Synapse after outbox dispatch");

                boolean found = false;
                for (LoggedRequest request : requests) {
                    JsonPath batchJson = JsonPath.from(request.getBodyAsString());
                    List<Map<String, Object>> instructions = batchJson.getList("transactions");
                    if (instructions != null && instructions.stream()
                            .anyMatch(instr -> savingsId.equals(((Number) instr.get("savingsAccountId")).intValue()))) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found, "Expected at least one batch request containing savingsAccountId=" + savingsId);
            } finally {
                globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                        new PutGlobalConfigurationsRequest().enabled(false));
            }
        }
    }

    @Nested
    class SchedulerRoundTrip {

        private static final String BATCH_URL = "/api/v1/proxy/savings/interest-postings:batch";
        private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

        @SuppressWarnings("unchecked")
        @Test
        void schedulerSendsBatchToSynapseAndReplayRecordsTransactions() {
            GlobalConfigurationHelper globalConfigHelper = new GlobalConfigurationHelper();
            try {
                globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                        new PutGlobalConfigurationsRequest().enabled(true));

                String activationDate = "01 January 2022";
                LocalDate postingDate = LocalDate.of(2022, 2, 2);
                BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE,
                        LocalDate.of(2022, 1, 1));

                Account[] gl = createCashBasedGlAccounts();
                Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, activationDate);
                Integer productId = SavingsProductHelper.createSavingsProduct(
                        new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily()
                                .withInterestPostingPeriodTypeAsDaily()
                                .withInterestCalculationPeriodTypeAsDailyBalance().withAccountingRuleAsCashBased(gl).build(),
                        requestSpec, responseSpec);
                SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
                Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", activationDate);
                sh.approveSavingsOnDate(savingsId, activationDate);
                sh.activateSavingsAccount(savingsId, activationDate);
                sh.depositToSavingsAccount(savingsId, "1000", activationDate, CommonConstants.RESPONSE_RESOURCE_ID);

                float balanceBefore = balanceOf(savingsId);

                BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, postingDate);

                synapse.resetRequests();

                SchedulerJobHelper schedulerJobHelper = new SchedulerJobHelper(requestSpec);
                schedulerJobHelper.executeAndAwaitJob("Post Interest For Savings");
                schedulerJobHelper.executeAndAwaitJob("Dispatch Synapse Outbox");

                List<LoggedRequest> requests = synapse.findAll(postRequestedFor(urlEqualTo(BATCH_URL)));
                assertFalse(requests.isEmpty(), "Expected at least one batch POST to Synapse");

                String batchBody = requests.get(0).getBodyAsString();
                JsonPath batchJson = JsonPath.from(batchBody);
                List<Map<String, Object>> instructions = batchJson.getList("transactions");
                assertNotNull(instructions, "Expected transactions in batch payload");
                assertFalse(instructions.isEmpty(), "Expected at least one instruction in batch");

                float totalReplayed = 0f;
                int replayedCount = 0;
                for (Map<String, Object> instruction : instructions) {
                    String traceId = (String) instruction.get("traceId");
                    Number amount = (Number) instruction.get("amount");
                    String txType = (String) instruction.get("transactionType");
                    String txDateStr = formatTransactionDate(instruction.get("transactionDate"));
                    String overdraftAmt = instruction.get("overdraftAmount") != null
                            ? instruction.get("overdraftAmount").toString()
                            : null;

                    String json = SavingsAccountHelper.buildReplayInterestPostingJson(amount.toString(), txDateStr, txType, traceId,
                            overdraftAmt);
                    Integer txnId = sh.replayInterestPosting(savingsId, json);
                    assertNotNull(txnId, "Replay should return a transaction id for traceId=" + traceId);
                    totalReplayed += amount.floatValue();
                    replayedCount++;
                }

                float balanceAfter = balanceOf(savingsId);
                assertEquals(balanceBefore + totalReplayed, balanceAfter, 0.01f,
                        "Balance should equal deposit + total replayed interest");

                HashMap details = sh.getSavingsDetails(savingsId);
                ArrayList<HashMap<String, Object>> transactions = (ArrayList<HashMap<String, Object>>) details.get("transactions");
                long interestPostings = transactions.stream()
                        .filter(tx -> Boolean.TRUE.equals(((HashMap) tx.get("transactionType")).get("interestPosting"))).count();
                assertEquals(replayedCount, interestPostings, "Number of interest posting transactions should match replayed count");
            } finally {
                globalConfigHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                        new PutGlobalConfigurationsRequest().enabled(false));
            }
        }

        private String formatTransactionDate(Object transactionDate) {
            if (transactionDate instanceof String s) {
                LocalDate date = LocalDate.parse(s);
                return date.format(DATE_FORMATTER);
            }
            if (transactionDate instanceof List<?> parts) {
                LocalDate date = LocalDate.of(((Number) parts.get(0)).intValue(), ((Number) parts.get(1)).intValue(),
                        ((Number) parts.get(2)).intValue());
                return date.format(DATE_FORMATTER);
            }
            return transactionDate.toString();
        }
    }

    // -- helpers: self-explanatory by name, no need to read their body to understand a test --

    private Account[] createCashBasedGlAccounts() {
        AccountHelper ah = new AccountHelper(requestSpec, responseSpec);
        return new Account[] { ah.createAssetAccount(), ah.createLiabilityAccount(), ah.createIncomeAccount(),
                ah.createExpenseAccount() };
    }

    private Integer createActiveSavingsWithDeposit(Account[] gl, String depositAmount) {
        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, DATE);
        Integer productId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                        .withInterestCalculationPeriodTypeAsDailyBalance().withAccountingRuleAsCashBased(gl).build(),
                requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", DATE);
        sh.approveSavingsOnDate(savingsId, DATE);
        sh.activateSavingsAccount(savingsId, DATE);
        sh.depositToSavingsAccount(savingsId, depositAmount, DATE, CommonConstants.RESPONSE_RESOURCE_ID);
        return savingsId;
    }

    private Integer createOverdrawnSavingsAccount(Account[] gl, String depositAmount, String withdrawalAmount) {
        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, DATE);
        Integer productId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                        .withInterestCalculationPeriodTypeAsDailyBalance().withOverDraft("10000").withAccountingRuleAsCashBased(gl).build(),
                requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", DATE);
        sh.approveSavingsOnDate(savingsId, DATE);
        sh.activateSavingsAccount(savingsId, DATE);
        sh.depositToSavingsAccount(savingsId, depositAmount, DATE, CommonConstants.RESPONSE_RESOURCE_ID);
        sh.withdrawalFromSavingsAccount(savingsId, withdrawalAmount, DATE, CommonConstants.RESPONSE_RESOURCE_ID);
        return savingsId;
    }

    private Integer createSavingsWithWithholdTaxAndDeposit(Account[] gl, String depositAmount) {
        Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, DATE);
        Integer taxComponentId = TaxComponentHelper.createTaxComponent(requestSpec, responseSpec, "10", null);
        Integer taxGroupId = TaxGroupHelper.createTaxGroup(requestSpec, responseSpec, Arrays.asList(taxComponentId));
        Integer productId = SavingsProductHelper.createSavingsProduct(
                new SavingsProductHelper().withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                        .withInterestCalculationPeriodTypeAsDailyBalance().withWithHoldTax(String.valueOf(taxGroupId))
                        .withAccountingRuleAsCashBased(gl).build(),
                requestSpec, responseSpec);
        SavingsAccountHelper sh = new SavingsAccountHelper(requestSpec, responseSpec);
        Integer savingsId = sh.applyForSavingsApplicationOnDate(clientId, productId, "INDIVIDUAL", DATE);
        sh.approveSavingsOnDate(savingsId, DATE);
        sh.activateSavingsAccount(savingsId, DATE);
        sh.depositToSavingsAccount(savingsId, depositAmount, DATE, CommonConstants.RESPONSE_RESOURCE_ID);
        return savingsId;
    }

    private Integer replayPosting(Integer savingsId, String amount, String type, String traceId) {
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        String json = SavingsAccountHelper.buildReplayInterestPostingJson(amount, DATE, type, traceId, null);
        return new SavingsAccountHelper(requestSpec, responseSpec).replayInterestPosting(savingsId, json);
    }

    @SuppressWarnings("unchecked")
    private float balanceOf(Integer savingsId) {
        return (Float) ((HashMap) new SavingsAccountHelper(requestSpec, responseSpec).getSavingsSummary(savingsId)).get("accountBalance");
    }

    @SuppressWarnings("unchecked")
    private HashMap transactionType(Integer savingsId, Integer txnId) {
        HashMap txn = new SavingsAccountHelper(requestSpec, responseSpec).getTransactionDetails(savingsId, txnId);
        return (HashMap) txn.get("transactionType");
    }

    @SuppressWarnings("unchecked")
    private float transactionAmount(Integer savingsId, Integer txnId) {
        HashMap txn = new SavingsAccountHelper(requestSpec, responseSpec).getTransactionDetails(savingsId, txnId);
        return ((Number) txn.get("amount")).floatValue();
    }


}