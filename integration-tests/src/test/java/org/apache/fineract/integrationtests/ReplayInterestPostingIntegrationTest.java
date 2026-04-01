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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.apache.fineract.integrationtests.common.savings.SavingsStatusChecker;
import org.apache.fineract.integrationtests.common.savings.SavingsTestLifecycleExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ SavingsTestLifecycleExtension.class })
public class ReplayInterestPostingIntegrationTest {

    private static final String ACCOUNT_TYPE_INDIVIDUAL = "INDIVIDUAL";
    private static final String START_DATE = "10 April 2022";

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private SavingsAccountHelper savingsAccountHelper;
    private SavingsProductHelper savingsProductHelper;
    private AccountHelper accountHelper;
    private JournalEntryHelper journalEntryHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.savingsProductHelper = new SavingsProductHelper();
        this.accountHelper = new AccountHelper(this.requestSpec, this.responseSpec);
        this.journalEntryHelper = new JournalEntryHelper(this.requestSpec, this.responseSpec);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testHappyPath_interestPostingReplayCreatesTransactionAndUpdatesBalance() {
        final Account assetAccount = this.accountHelper.createAssetAccount();
        final Account liabilityAccount = this.accountHelper.createLiabilityAccount();
        final Account incomeAccount = this.accountHelper.createIncomeAccount();
        final Account expenseAccount = this.accountHelper.createExpenseAccount();

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec, START_DATE);
        assertNotNull(clientID);

        final String savingsProductJSON = this.savingsProductHelper.withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance()
                .withAccountingRuleAsCashBased(new Account[] { assetAccount, liabilityAccount, incomeAccount, expenseAccount }).build();
        final Integer savingsProductID = SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
        assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplicationOnDate(clientID, savingsProductID,
                ACCOUNT_TYPE_INDIVIDUAL, START_DATE);
        assertNotNull(savingsId);
        HashMap savingsStatusHashMap = this.savingsAccountHelper.approveSavingsOnDate(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);
        savingsStatusHashMap = this.savingsAccountHelper.activateSavingsAccount(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        Integer depositTxnId = (Integer) this.savingsAccountHelper.depositToSavingsAccount(savingsId, "1000", START_DATE,
                CommonConstants.RESPONSE_RESOURCE_ID);
        assertNotNull(depositTxnId);

        HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balanceBefore = (Float) summaryBefore.get("accountBalance");

        String replayJson = SavingsAccountHelper.buildReplayInterestPostingJson("50.00", START_DATE, "INTEREST_POSTING",
                UUID.randomUUID().toString(), null);
        Integer replayTxnId = this.savingsAccountHelper.replayInterestPosting(savingsId, replayJson);
        assertNotNull(replayTxnId);

        HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balanceAfter = (Float) summaryAfter.get("accountBalance");
        assertEquals(balanceBefore + 50.0f, balanceAfter, 0.01f, "Balance should increase by 50.00 after interest posting replay");

        HashMap txnDetails = this.savingsAccountHelper.getTransactionDetails(savingsId, replayTxnId);
        HashMap transactionType = (HashMap) txnDetails.get("transactionType");
        assertTrue((Boolean) transactionType.get("interestPosting"), "Transaction type should be interest posting");

        ArrayList<HashMap> journalEntries = this.journalEntryHelper.getJournalEntriesByTransactionId("S" + replayTxnId);
        assertFalse(journalEntries.isEmpty(), "Journal entries should be created for replay transaction");
        boolean expenseDebitFound = false;
        boolean liabilityCreditFound = false;
        for (Map<String, Object> entry : journalEntries) {
            String entryType = (String) ((HashMap) entry.get("entryType")).get("value");
            Integer glAccountId = ((Number) entry.get("glAccountId")).intValue();
            if ("DEBIT".equals(entryType) && glAccountId.equals(expenseAccount.getAccountID())) {
                expenseDebitFound = true;
            }
            if ("CREDIT".equals(entryType) && glAccountId.equals(liabilityAccount.getAccountID())) {
                liabilityCreditFound = true;
            }
        }
        assertTrue(expenseDebitFound, "DEBIT to expense account (interest on savings) should exist");
        assertTrue(liabilityCreditFound, "CREDIT to liability account (savings control) should exist");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testIdempotency_sameTraceIdReturnsSameTransaction() {
        final Account assetAccount = this.accountHelper.createAssetAccount();
        final Account liabilityAccount = this.accountHelper.createLiabilityAccount();
        final Account incomeAccount = this.accountHelper.createIncomeAccount();
        final Account expenseAccount = this.accountHelper.createExpenseAccount();

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec, START_DATE);
        assertNotNull(clientID);

        final String savingsProductJSON = this.savingsProductHelper.withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance()
                .withAccountingRuleAsCashBased(new Account[] { assetAccount, liabilityAccount, incomeAccount, expenseAccount }).build();
        final Integer savingsProductID = SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
        assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplicationOnDate(clientID, savingsProductID,
                ACCOUNT_TYPE_INDIVIDUAL, START_DATE);
        assertNotNull(savingsId);
        HashMap savingsStatusHashMap = this.savingsAccountHelper.approveSavingsOnDate(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);
        savingsStatusHashMap = this.savingsAccountHelper.activateSavingsAccount(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "1000", START_DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        String fixedTraceId = UUID.randomUUID().toString();
        String replayJson = SavingsAccountHelper.buildReplayInterestPostingJson("50.00", START_DATE, "INTEREST_POSTING", fixedTraceId,
                null);

        Integer firstTxnId = this.savingsAccountHelper.replayInterestPosting(savingsId, replayJson);
        assertNotNull(firstTxnId);

        Integer secondTxnId = this.savingsAccountHelper.replayInterestPosting(savingsId, replayJson);
        assertNotNull(secondTxnId);

        assertEquals(firstTxnId, secondTxnId, "Idempotent replay should return the same transaction ID");

        HashMap summary = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balance = (Float) summary.get("accountBalance");
        assertEquals(1050.0f, balance, 0.01f, "Balance should reflect only one posting of 50.00");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testOverdraftInterestReplayCreatesDebitTransaction() {
        final Account assetAccount = this.accountHelper.createAssetAccount();
        final Account liabilityAccount = this.accountHelper.createLiabilityAccount();
        final Account incomeAccount = this.accountHelper.createIncomeAccount();
        final Account expenseAccount = this.accountHelper.createExpenseAccount();

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec, START_DATE);
        assertNotNull(clientID);

        final String savingsProductJSON = this.savingsProductHelper.withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance().withOverDraft("10000")
                .withAccountingRuleAsCashBased(new Account[] { assetAccount, liabilityAccount, incomeAccount, expenseAccount }).build();
        final Integer savingsProductID = SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
        assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplicationOnDate(clientID, savingsProductID,
                ACCOUNT_TYPE_INDIVIDUAL, START_DATE);
        assertNotNull(savingsId);
        HashMap savingsStatusHashMap = this.savingsAccountHelper.approveSavingsOnDate(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);
        savingsStatusHashMap = this.savingsAccountHelper.activateSavingsAccount(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "500", START_DATE, CommonConstants.RESPONSE_RESOURCE_ID);
        this.savingsAccountHelper.withdrawalFromSavingsAccount(savingsId, "1000", START_DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balanceBefore = (Float) summaryBefore.get("accountBalance");
        assertEquals(-500.0f, balanceBefore, 0.01f, "Balance should be -500 after deposit 500 and withdrawal 1000");

        String replayJson = SavingsAccountHelper.buildReplayInterestPostingJson("25.00", START_DATE, "OVERDRAFT_INTEREST",
                UUID.randomUUID().toString(), "25.00");
        Integer replayTxnId = this.savingsAccountHelper.replayInterestPosting(savingsId, replayJson);
        assertNotNull(replayTxnId);

        HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balanceAfter = (Float) summaryAfter.get("accountBalance");
        assertEquals(balanceBefore - 25.0f, balanceAfter, 0.01f, "Balance should decrease by exactly 25.00 after overdraft interest");

        HashMap txnDetails = this.savingsAccountHelper.getTransactionDetails(savingsId, replayTxnId);
        HashMap transactionType = (HashMap) txnDetails.get("transactionType");
        assertTrue((Boolean) transactionType.get("overdraftInterest"), "Transaction type should be overdraft interest");
        assertEquals(25.0f, ((Number) txnDetails.get("amount")).floatValue(), 0.01f, "Transaction amount should be 25.00");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testWithholdTaxReplayCreatesDebitTransaction() {
        final Account assetAccount = this.accountHelper.createAssetAccount();
        final Account liabilityAccount = this.accountHelper.createLiabilityAccount();
        final Account incomeAccount = this.accountHelper.createIncomeAccount();
        final Account expenseAccount = this.accountHelper.createExpenseAccount();

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec, START_DATE);
        assertNotNull(clientID);

        final Integer taxComponentId = TaxComponentHelper.createTaxComponent(this.requestSpec, this.responseSpec, "10", null);
        final Integer taxGroupId = TaxGroupHelper.createTaxGroup(this.requestSpec, this.responseSpec, Arrays.asList(taxComponentId));

        final String savingsProductJSON = this.savingsProductHelper.withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance()
                .withWithHoldTax(String.valueOf(taxGroupId))
                .withAccountingRuleAsCashBased(new Account[] { assetAccount, liabilityAccount, incomeAccount, expenseAccount }).build();
        final Integer savingsProductID = SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
        assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplicationOnDate(clientID, savingsProductID,
                ACCOUNT_TYPE_INDIVIDUAL, START_DATE);
        assertNotNull(savingsId);
        HashMap savingsStatusHashMap = this.savingsAccountHelper.approveSavingsOnDate(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);
        savingsStatusHashMap = this.savingsAccountHelper.activateSavingsAccount(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "1000", START_DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balanceBefore = (Float) summaryBefore.get("accountBalance");
        assertEquals(1000.0f, balanceBefore, 0.01f, "Balance should be 1000 after deposit");

        String replayJson = SavingsAccountHelper.buildReplayInterestPostingJson("30.00", START_DATE, "WITHHOLD_TAX",
                UUID.randomUUID().toString(), null);
        Integer replayTxnId = this.savingsAccountHelper.replayInterestPosting(savingsId, replayJson);
        assertNotNull(replayTxnId);

        HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balanceAfter = (Float) summaryAfter.get("accountBalance");
        assertEquals(970.0f, balanceAfter, 0.01f, "Balance should decrease by exactly 30.00 after withhold tax");

        HashMap txnDetails = this.savingsAccountHelper.getTransactionDetails(savingsId, replayTxnId);
        HashMap transactionType = (HashMap) txnDetails.get("transactionType");
        assertTrue((Boolean) transactionType.get("withholdTax"), "Transaction type should be withhold tax");
        assertEquals(30.0f, ((Number) txnDetails.get("amount")).floatValue(), 0.01f, "Transaction amount should be 30.00");
    }

    @Test
    public void testUnknownTransactionTypeReturnsError() {
        final Account assetAccount = this.accountHelper.createAssetAccount();
        final Account liabilityAccount = this.accountHelper.createLiabilityAccount();
        final Account incomeAccount = this.accountHelper.createIncomeAccount();
        final Account expenseAccount = this.accountHelper.createExpenseAccount();

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec, START_DATE);
        assertNotNull(clientID);

        final String savingsProductJSON = this.savingsProductHelper.withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance()
                .withAccountingRuleAsCashBased(new Account[] { assetAccount, liabilityAccount, incomeAccount, expenseAccount }).build();
        final Integer savingsProductID = SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
        assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplicationOnDate(clientID, savingsProductID,
                ACCOUNT_TYPE_INDIVIDUAL, START_DATE);
        assertNotNull(savingsId);
        HashMap savingsStatusHashMap = this.savingsAccountHelper.approveSavingsOnDate(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);
        savingsStatusHashMap = this.savingsAccountHelper.activateSavingsAccount(savingsId, START_DATE);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "1000", START_DATE, CommonConstants.RESPONSE_RESOURCE_ID);

        ResponseSpecification errorResponseSpec = new ResponseSpecBuilder().expectStatusCode(500).build();
        SavingsAccountHelper errorHelper = new SavingsAccountHelper(this.requestSpec, errorResponseSpec);

        String replayJson = SavingsAccountHelper.buildReplayInterestPostingJson("50.00", START_DATE, "INVALID_TYPE",
                UUID.randomUUID().toString(), null);
        errorHelper.replayInterestPosting(savingsId, replayJson);
    }
}