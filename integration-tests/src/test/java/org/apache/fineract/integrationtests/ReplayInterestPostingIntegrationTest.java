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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.TaxComponentHelper;
import org.apache.fineract.integrationtests.common.TaxGroupHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.JournalEntryHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsTestLifecycleExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ SavingsTestLifecycleExtension.class })
public class ReplayInterestPostingIntegrationTest {

    private static final String DATE = "10 April 2022";

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
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