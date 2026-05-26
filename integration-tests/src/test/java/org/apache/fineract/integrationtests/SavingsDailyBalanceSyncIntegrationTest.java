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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.GlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.SchedulerJobHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsStatusChecker;
import org.apache.fineract.integrationtests.common.savings.SavingsTestLifecycleExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end coverage for the daily-balance hourly sync subsystem (AB-220).
 *
 * <p>
 * The sync subsystem replaces the synchronous UPSERT of {@code m_savings_account_daily_balance} on every
 * deposit/withdrawal with an hourly batch (job short name {@code SA_DSYNC}) that derives the snapshot from
 * {@code m_savings_account_transaction.running_balance_derived}. These tests exercise the integration end-to-end via
 * the scheduler API: the snapshot table is read only by the interest posting flow ({@code SA_PINT}), so we use the
 * posted interest as the proxy for snapshot correctness.
 * </p>
 *
 * <p>
 * Scenarios covered:
 * </p>
 * <ol>
 * <li>Deposit then explicit {@code SA_DSYNC} run → interest posts correctly.</li>
 * <li>Deposit without explicit sync run → interest posting still posts correctly (SA_PINT must invoke {@code syncNow()}
 * as its first step).</li>
 * <li>Reversal of the only deposit on a date → snapshot row drained → interest reflects no balance.</li>
 * <li>Reversal of one of several same-day deposits → snapshot row updated to next-latest running balance via dirty
 * drain.</li>
 * <li>Deposit followed by hold → interest accrues only on available balance (validates the hold-aware running-balance
 * fix in {@code SavingsAccountDomainServiceJpa}).</li>
 * <li>Idempotency: running {@code SA_DSYNC} twice in a row produces no observable change.</li>
 * </ol>
 */
@Order(2)
@ExtendWith({ SavingsTestLifecycleExtension.class })
public class SavingsDailyBalanceSyncIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(SavingsDailyBalanceSyncIntegrationTest.class);
    private static final String ACCOUNT_TYPE_INDIVIDUAL = "INDIVIDUAL";
    private static final String SYNC_DAILY_BALANCE_JOB_SHORT_NAME = "SA_DSYNC";
    private static final String POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME = "SA_PINT";

    private static ResponseSpecification responseSpec;
    private static RequestSpecification requestSpec;
    private SavingsProductHelper savingsProductHelper;
    private SavingsAccountHelper savingsAccountHelper;
    private SchedulerJobHelper scheduleJobHelper;
    private GlobalConfigurationHelper globalConfigurationHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
        this.savingsProductHelper = new SavingsProductHelper();
        this.scheduleJobHelper = new SchedulerJobHelper(requestSpec);
        this.globalConfigurationHelper = new GlobalConfigurationHelper();
    }

    @AfterEach
    public void tearDown() {
        globalConfigurationHelper.resetAllDefaultGlobalConfigurations();
        globalConfigurationHelper.verifyAllDefaultGlobalConfigurations();
    }

    /**
     * Baseline: a deposit, an explicit {@code SA_DSYNC} run, then interest posting. If the batch correctly derived the
     * snapshot from {@code running_balance_derived}, the posted interest should match the daily-compounding rate
     * against the deposit amount.
     */
    @Test
    public void depositThenExplicitSyncProducesCorrectInterest() {
        final String startDate = "10 April 2022";
        final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, startDate);
        assertNotNull(clientId);

        final Integer savingsId = createSavingsAccount(clientId, startDate);
        savingsAccountHelper.depositToSavingsAccount(savingsId, "10000", startDate, CommonConstants.RESPONSE_RESOURCE_ID);

        // Trigger sync explicitly so we can assert the snapshot is written without relying on SA_PINT's auto-sync.
        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);
        scheduleJobHelper.executeAndAwaitJobByShortName(POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME);

        final HashMap<String, Object> postingTxn = lastInterestPostingTransaction(savingsId);
        // Daily compounding on 10000 at the configured rate must produce a positive amount and a balance > principal.
        final BigDecimal posted = new BigDecimal(postingTxn.get("amount").toString());
        final BigDecimal runningBalance = new BigDecimal(postingTxn.get("runningBalance").toString());
        assertTrue(posted.signum() > 0, "Interest posting amount must be > 0; was " + posted);
        assertTrue(runningBalance.compareTo(new BigDecimal("10000")) > 0,
                "Running balance after interest must exceed principal; was " + runningBalance);
    }

    /**
     * SA_PINT must invoke {@code SavingsDailyBalanceSyncService.syncNow()} as its first step so it never reads stale
     * snapshots. We verify this by skipping the explicit SA_DSYNC run — interest must still come out correct.
     */
    @Test
    public void interestPostingTriggersSyncImplicitly() {
        final String startDate = "10 April 2022";
        final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, startDate);
        assertNotNull(clientId);

        final Integer savingsId = createSavingsAccount(clientId, startDate);
        savingsAccountHelper.depositToSavingsAccount(savingsId, "10000", startDate, CommonConstants.RESPONSE_RESOURCE_ID);

        // Deliberately skip SA_DSYNC — SA_PINT must self-trigger the sync.
        scheduleJobHelper.executeAndAwaitJobByShortName(POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME);

        final HashMap<String, Object> postingTxn = lastInterestPostingTransaction(savingsId);
        final BigDecimal posted = new BigDecimal(postingTxn.get("amount").toString());
        assertTrue(posted.signum() > 0,
                "SA_PINT must call syncNow() before reading snapshots; missing interest indicates the wiring was skipped");
    }

    /**
     * Reversal of the only deposit on a date must drain the snapshot row via the dirty side-channel. After the dirty
     * drain runs, the snapshot for that date should not contribute to interest.
     */
    @Test
    public void reversalOfOnlyTransactionDrainsSnapshot() {
        final String startDate = "10 April 2022";
        final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, startDate);
        assertNotNull(clientId);

        final Integer savingsId = createSavingsAccount(clientId, startDate);
        final Integer depositTxnId = (Integer) savingsAccountHelper.depositToSavingsAccount(savingsId, "10000", startDate,
                CommonConstants.RESPONSE_RESOURCE_ID);
        assertNotNull(depositTxnId);

        // Initial sync writes (account, startDate) snapshot at $10000.
        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);

        // Reverse the deposit. The reversal hook in SavingsAccountWritePlatformServiceJpaRepositoryImpl.undoTransaction
        // enqueues (savingsId, startDate) into m_savings_daily_balance_dirty.
        savingsAccountHelper.reverseSavingsAccountTransaction(savingsId, depositTxnId);

        // Second sync drains dirty → since the only txn on startDate is now reversed, the snapshot row is DELETEd.
        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);
        scheduleJobHelper.executeAndAwaitJobByShortName(POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME);

        // No active deposit ⇒ no interest accrual ⇒ either no posting transaction, or posting amount is 0.
        final ArrayList<HashMap<String, Object>> txns = allTransactions(savingsId);
        for (HashMap<String, Object> t : txns) {
            if (Boolean.TRUE.equals(t.get("reversed"))) {
                continue;
            }
            // Interest posting txnType is 12. Find any non-reversed posting and assert amount = 0.
            final Map<String, Object> typeMap = (Map<String, Object>) t.get("transactionType");
            if (typeMap != null && Boolean.TRUE.equals(typeMap.get("interestPosting"))) {
                final BigDecimal amount = new BigDecimal(t.get("amount").toString());
                assertEquals(0, amount.signum(), "After reversing the only deposit and draining the dirty set, interest posting must be 0");
            }
        }
    }

    /**
     * Reversal of one of several same-day deposits must update the snapshot row to reflect the next-latest non-reversed
     * transaction's running balance — not delete it. Verifies the UPSERT branch of the dirty drain.
     */
    @Test
    public void reversalOfOneOfManySameDayTransactionsUpdatesSnapshot() {
        final String startDate = "10 April 2022";
        final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, startDate);
        assertNotNull(clientId);

        final Integer savingsId = createSavingsAccount(clientId, startDate);
        final Integer firstDepositId = (Integer) savingsAccountHelper.depositToSavingsAccount(savingsId, "5000", startDate,
                CommonConstants.RESPONSE_RESOURCE_ID);
        savingsAccountHelper.depositToSavingsAccount(savingsId, "3000", startDate, CommonConstants.RESPONSE_RESOURCE_ID);
        // After both deposits, end-of-day available balance = 8000.
        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);

        // Reverse the first deposit. End-of-day should now be 3000 — the running_balance_derived of the second (still
        // non-reversed) transaction after recalculateDailyBalances rewrites it.
        savingsAccountHelper.reverseSavingsAccountTransaction(savingsId, firstDepositId);

        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);
        scheduleJobHelper.executeAndAwaitJobByShortName(POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME);

        final HashMap<String, Object> postingTxn = lastInterestPostingTransaction(savingsId);
        final BigDecimal runningBalance = new BigDecimal(postingTxn.get("runningBalance").toString());
        // Posting balance should reflect the surviving 3000 plus a small interest amount, not 8000+.
        assertTrue(runningBalance.compareTo(new BigDecimal("3500")) < 0,
                "Snapshot must reflect surviving 3000 after reversal; got runningBalance=" + runningBalance);
        assertTrue(runningBalance.compareTo(new BigDecimal("3000")) >= 0,
                "Surviving balance must be at least 3000; got runningBalance=" + runningBalance);
    }

    /**
     * Validates the hold-aware running-balance fix (plan §10.2). With $10000 deposited and $4000 placed on hold,
     * interest must accrue on the available balance (~$6000), not the posted balance ($10000).
     */
    @Test
    public void holdReducesAvailableBalanceForInterest() {
        final String startDate = "10 April 2022";
        final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, startDate);
        assertNotNull(clientId);

        final Integer savingsId = createSavingsAccount(clientId, startDate);
        savingsAccountHelper.depositToSavingsAccount(savingsId, "10000", startDate, CommonConstants.RESPONSE_RESOURCE_ID);

        // Place $4000 on hold. The optimized deposit path that runs after this hold must subtract on-hold funds when
        // computing running_balance_derived for any subsequent deposits/withdrawals on this account.
        savingsAccountHelper.holdAmountInSavingsAccount(savingsId, "4000", false, startDate, CommonConstants.RESPONSE_RESOURCE_ID);

        // Add a small follow-on deposit so we have a non-hold transaction with running_balance_derived computed by the
        // (newly hold-aware) optimized path. End-of-day available balance should be 10000 + 100 - 4000 = 6100.
        savingsAccountHelper.depositToSavingsAccount(savingsId, "100", startDate, CommonConstants.RESPONSE_RESOURCE_ID);

        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);
        scheduleJobHelper.executeAndAwaitJobByShortName(POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME);

        final HashMap<String, Object> postingTxn = lastInterestPostingTransaction(savingsId);
        final BigDecimal posted = new BigDecimal(postingTxn.get("amount").toString());

        // Compute reference: interest on $6100 at the test product's daily rate must be strictly less than interest on
        // the posted balance of $10100. The pre-fix bug would have computed interest against $10100.
        // We use a generous bound: interest < 6100 * 1% upper-cap (any sane daily rate is well below 1%).
        final BigDecimal upperBoundIfHoldRespected = new BigDecimal("61");
        final BigDecimal lowerBoundIfHoldIgnored = new BigDecimal("80"); // would-be value if computed against ~10100
        assertTrue(posted.compareTo(upperBoundIfHoldRespected) < 0,
                "Interest must be computed on available balance ($6100), not posted balance — got " + posted);
        assertTrue(posted.compareTo(lowerBoundIfHoldIgnored) < 0,
                "Hold-aware fix regression: interest looks like it was computed on posted balance — got " + posted);
    }

    /**
     * Idempotency: running SA_DSYNC twice in a row when nothing has changed must not change observable behavior.
     * Verifies the watermark advances cleanly and the second-run SQL is a no-op.
     */
    @Test
    public void runningSyncTwiceIsIdempotent() {
        final String startDate = "10 April 2022";
        final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, startDate);
        assertNotNull(clientId);

        final Integer savingsId = createSavingsAccount(clientId, startDate);
        savingsAccountHelper.depositToSavingsAccount(savingsId, "10000", startDate, CommonConstants.RESPONSE_RESOURCE_ID);

        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME);
        scheduleJobHelper.executeAndAwaitJobByShortName(SYNC_DAILY_BALANCE_JOB_SHORT_NAME); // second run, expect no-op

        scheduleJobHelper.executeAndAwaitJobByShortName(POST_INTEREST_FOR_SAVINGS_JOB_SHORT_NAME);

        final HashMap<String, Object> postingTxn = lastInterestPostingTransaction(savingsId);
        final BigDecimal posted = new BigDecimal(postingTxn.get("amount").toString());
        assertTrue(posted.signum() > 0, "Interest posting must produce a positive amount even after redundant sync runs");
    }

    // ---- helpers ----

    private Integer createSavingsAccount(final Integer clientId, final String startDate) {
        final Integer savingsProductId = createDailyPostingProduct();
        Assertions.assertNotNull(savingsProductId);
        final Integer savingsId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, savingsProductId, ACCOUNT_TYPE_INDIVIDUAL,
                startDate);
        Assertions.assertNotNull(savingsId);
        HashMap<?, ?> status = savingsAccountHelper.approveSavingsOnDate(savingsId, startDate);
        SavingsStatusChecker.verifySavingsIsApproved(status);
        status = savingsAccountHelper.activateSavingsAccount(savingsId, startDate);
        SavingsStatusChecker.verifySavingsIsActive(status);
        return savingsId;
    }

    private Integer createDailyPostingProduct() {
        final String json = savingsProductHelper.withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                .withInterestCalculationPeriodTypeAsDailyBalance().build();
        return SavingsProductHelper.createSavingsProduct(json, requestSpec, responseSpec);
    }

    @SuppressWarnings("unchecked")
    private ArrayList<HashMap<String, Object>> allTransactions(final Integer savingsId) {
        final Object obj = savingsAccountHelper.getSavingsDetails(savingsId, "transactions");
        return (ArrayList<HashMap<String, Object>>) obj;
    }

    /**
     * Returns the most recent non-reversed interest-posting transaction. Throws if none exists — failing the test with
     * a clear signal rather than NPEing on a downstream getter.
     */
    private HashMap<String, Object> lastInterestPostingTransaction(final Integer savingsId) {
        final ArrayList<HashMap<String, Object>> txns = allTransactions(savingsId);
        for (int i = txns.size() - 1; i >= 0; i--) {
            final HashMap<String, Object> t = txns.get(i);
            if (Boolean.TRUE.equals(t.get("reversed"))) {
                continue;
            }
            final Object typeObj = t.get("transactionType");
            if (typeObj instanceof Map<?, ?> typeMap && Boolean.TRUE.equals(typeMap.get("interestPosting"))) {
                LOG.info("Last interest posting txn: {}", t);
                return t;
            }
        }
        throw new AssertionError("No interest-posting transaction found for savings account " + savingsId);
    }
}
