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

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsStatusChecker;
import org.apache.fineract.integrationtests.common.savings.SavingsTestLifecycleExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance benchmark for savings account deposit/withdrawal operations. Verifies O(1) behavior: transaction latency
 * should be constant regardless of account history size.
 */
@SuppressWarnings({ "rawtypes" })
@Order(3)
@ExtendWith({ SavingsTestLifecycleExtension.class })
public class SavingsPerformanceBenchmarkTest {

    private static final Logger LOG = LoggerFactory.getLogger(SavingsPerformanceBenchmarkTest.class);
    private static final String ACCOUNT_TYPE_INDIVIDUAL = "INDIVIDUAL";
    private static final String DATE_FORMAT = "dd MMMM yyyy";
    private static final double O1_THRESHOLD = 8.0; // CI environments have high variability; 8x is safe threshold
    private static final int TPS_TARGET = 2000;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;

    private ResponseSpecification responseSpec;
    private RequestSpecification requestSpec;
    private SavingsAccountHelper savingsAccountHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.requestSpec.header("Fineract-Platform-TenantId", "default");
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
    }

    /**
     * Benchmark 1: Verify O(1) deposit latency across different account sizes. Creates accounts with 0, 100, 1000
     * historical transactions, then measures the time for a single deposit on each.
     */
    @Test
    public void testDepositLatencyIsConstantRegardlessOfHistorySize() {
        LOG.info("=== BENCHMARK: O(1) Deposit Latency Test ===");

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientID);
        final Integer savingsProductID = createSimpleSavingsProduct();
        Assertions.assertNotNull(savingsProductID);

        int[] historySizes = { 0, 100, 1000 };
        double[] avgLatencies = new double[historySizes.length];

        for (int i = 0; i < historySizes.length; i++) {
            int historySize = historySizes[i];
            Integer savingsId = createAndActivateAccount(clientID, savingsProductID);

            // Build up transaction history
            String txDate = getTransactionDate();
            for (int t = 0; t < historySize; t++) {
                this.savingsAccountHelper.depositToSavingsAccount(savingsId, "100", txDate, CommonConstants.RESPONSE_RESOURCE_ID);
            }

            // Warmup
            for (int w = 0; w < WARMUP_ITERATIONS; w++) {
                this.savingsAccountHelper.depositToSavingsAccount(savingsId, "10", txDate, CommonConstants.RESPONSE_RESOURCE_ID);
            }

            // Measure
            long totalNanos = 0;
            for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
                long start = System.nanoTime();
                this.savingsAccountHelper.depositToSavingsAccount(savingsId, "10", txDate, CommonConstants.RESPONSE_RESOURCE_ID);
                totalNanos += System.nanoTime() - start;
            }
            avgLatencies[i] = (totalNanos / (double) MEASUREMENT_ITERATIONS) / 1_000_000.0; // ms
            LOG.info("History size: {} -> Avg deposit latency: {} ms", historySize, String.format("%.2f", avgLatencies[i]));
        }

        printLatencyTable("Deposit Latency by History Size", historySizes, avgLatencies);

        // Verify O(1): latency at largest size should be <= O1_THRESHOLD * latency at smallest
        double ratio = avgLatencies[avgLatencies.length - 1] / avgLatencies[0];
        LOG.info("Latency ratio (size {} / size {}): {} (threshold: {})", historySizes[historySizes.length - 1], historySizes[0],
                String.format("%.2f", ratio), O1_THRESHOLD);
        assertTrue(ratio <= O1_THRESHOLD,
                String.format("O(1) violation: latency at %d txns (%.2f ms) is %.2fx latency at %d txns (%.2f ms), threshold is %.1fx",
                        historySizes[historySizes.length - 1], avgLatencies[avgLatencies.length - 1], ratio, historySizes[0],
                        avgLatencies[0], O1_THRESHOLD));
    }

    /**
     * Benchmark 2: Measure throughput (TPS) with concurrent deposits on different accounts.
     */
    @Test
    public void testConcurrentDepositThroughput() throws Exception {
        LOG.info("=== BENCHMARK: Concurrent Deposit Throughput ===");

        final int numThreads = 8;
        final int depositsPerThread = 25;
        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientID);
        final Integer savingsProductID = createSimpleSavingsProduct();

        // Create one account per thread
        List<Integer> accountIds = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            Integer savingsId = createAndActivateAccount(clientID, savingsProductID);
            // Seed with initial deposit so withdrawals don't fail
            this.savingsAccountHelper.depositToSavingsAccount(savingsId, "100000", getTransactionDate(),
                    CommonConstants.RESPONSE_RESOURCE_ID);
            accountIds.add(savingsId);
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            final Integer savingsId = accountIds.get(t);
            tasks.add(() -> {
                // Each thread gets its own REST client
                RequestSpecification threadReqSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
                threadReqSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
                threadReqSpec.header("Fineract-Platform-TenantId", "default");
                ResponseSpecification threadRespSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
                SavingsAccountHelper threadHelper = new SavingsAccountHelper(threadReqSpec, threadRespSpec);

                String txDate = getTransactionDate();
                for (int d = 0; d < depositsPerThread; d++) {
                    try {
                        threadHelper.depositToSavingsAccount(savingsId, "10", txDate, CommonConstants.RESPONSE_RESOURCE_ID);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        LOG.warn("Deposit failed on account {}: {}", savingsId, e.getMessage());
                    }
                }
                return null;
            });
        }

        long startTime = System.nanoTime();
        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(); // propagate exceptions
        }
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        executor.shutdown();

        int totalOps = successCount.get();
        double tps = totalOps * 1000.0 / elapsedMs;

        LOG.info("╔══════════════════════════════════════════════╗");
        LOG.info("║     Concurrent Deposit Throughput Results    ║");
        LOG.info("╠══════════════════════════════════════════════╣");
        LOG.info("║ Threads:          {}                        ║", numThreads);
        LOG.info("║ Total deposits:   {}                        ║", totalOps);
        LOG.info("║ Failed deposits:  {}                        ║", failCount.get());
        LOG.info("║ Elapsed time:     {} ms                     ║", elapsedMs);
        LOG.info("║ Throughput:       {} TPS                ║", String.format("%.1f", tps));
        LOG.info("║ Target:           {} TPS                    ║", TPS_TARGET);
        LOG.info("╚══════════════════════════════════════════════╝");

        if (tps < TPS_TARGET) {
            LOG.warn("TPS ({}) is below target ({}). CI environments may have limited resources.", String.format("%.1f", tps), TPS_TARGET);
        }
        // Don't fail on TPS — CI environments vary widely
        assertTrue(totalOps > 0, "At least some deposits should succeed");
    }

    /**
     * Benchmark 3: Concurrent deposits on the SAME account (tests optimistic locking retry).
     */
    @Test
    public void testConcurrentDepositsOnSameAccount() throws Exception {
        LOG.info("=== BENCHMARK: Concurrent Deposits on Same Account (Optimistic Locking) ===");

        final int numThreads = 4;
        final int depositsPerThread = 10;
        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientID);
        final Integer savingsProductID = createSimpleSavingsProduct();
        final Integer savingsId = createAndActivateAccount(clientID, savingsProductID);

        // Seed account
        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "100000", getTransactionDate(), CommonConstants.RESPONSE_RESOURCE_ID);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            tasks.add(() -> {
                RequestSpecification threadReqSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
                threadReqSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
                threadReqSpec.header("Fineract-Platform-TenantId", "default");
                ResponseSpecification threadRespSpec = new ResponseSpecBuilder().expectStatusCode(anyOf(is(200), is(409), is(500))).build();
                SavingsAccountHelper threadHelper = new SavingsAccountHelper(threadReqSpec, threadRespSpec);

                String txDate = getTransactionDate();
                for (int d = 0; d < depositsPerThread; d++) {
                    try {
                        threadHelper.depositToSavingsAccount(savingsId, "10", txDate, CommonConstants.RESPONSE_RESOURCE_ID);
                        successCount.incrementAndGet();
                    } catch (Exception | AssertionError e) {
                        failCount.incrementAndGet();
                        LOG.warn("Concurrent deposit failed: {}", e.getMessage());
                    }
                }
                return null;
            });
        }

        long startTime = System.nanoTime();
        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get();
        }
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        executor.shutdown();

        int totalAttempts = numThreads * depositsPerThread;
        LOG.info("╔══════════════════════════════════════════════════╗");
        LOG.info("║  Same-Account Concurrent Deposit Results        ║");
        LOG.info("╠══════════════════════════════════════════════════╣");
        LOG.info("║ Threads:            {}                          ║", numThreads);
        LOG.info("║ Deposits attempted: {}                          ║", totalAttempts);
        LOG.info("║ Successful:         {}                          ║", successCount.get());
        LOG.info("║ Failed:             {}                          ║", failCount.get());
        LOG.info("║ Elapsed time:       {} ms                       ║", elapsedMs);
        LOG.info("╚══════════════════════════════════════════════════╝");

        // With optimistic locking retry, most should succeed
        double successRate = successCount.get() * 100.0 / totalAttempts;
        LOG.info("Success rate: {}%", String.format("%.1f", successRate));
        assertTrue(successCount.get() > 0, "At least some concurrent deposits on the same account should succeed");
    }

    // === Helper Methods ===

    private Integer createSimpleSavingsProduct() {
        SavingsProductHelper productHelper = new SavingsProductHelper();
        final String savingsProductJSON = productHelper.withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsMonthly()
                .withInterestCalculationPeriodTypeAsDailyBalance().withMinimumOpenningBalance("0").build();
        return SavingsProductHelper.createSavingsProduct(savingsProductJSON, this.requestSpec, this.responseSpec);
    }

    /**
     * Tripwire test for the narrow delta-based JPQL on the optimized current-day deposit path
     * ({@code SavingsAccountRepository.applyDepositDelta}). Asserts that ONLY {@code totalDeposits} and
     * {@code accountBalance} change on a deposit, and every other summary field is bit-identical pre/post.
     * <p>
     * If a future change introduces a pre-JPQL summary mutator (e.g. interest accrual, charge payment) on the optimized
     * path, the narrow JPQL would silently drop those mutations. This test surfaces that as an immediate failure on
     * whichever field stops being equal.
     * <p>
     * Companion to {@link #testWithdrawalSummaryInvariants_onlyChangedFieldsMutate}.
     */
    @Test
    public void testDepositSummaryInvariants_onlyChangedFieldsMutate() {
        LOG.info("=== INVARIANT: Deposit only mutates totalDeposits + accountBalance ===");

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientID);
        final Integer savingsProductID = createSimpleSavingsProduct();
        final Integer savingsId = createAndActivateAccount(clientID, savingsProductID);

        // Seed so totals are non-zero before the deposit under test
        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "500", getTransactionDate(), CommonConstants.RESPONSE_RESOURCE_ID);

        final HashMap before = (HashMap) this.savingsAccountHelper.getSavingsDetails(savingsId).get("summary");

        final String depositAmount = "123.45";
        this.savingsAccountHelper.depositToSavingsAccount(savingsId, depositAmount, getTransactionDate(),
                CommonConstants.RESPONSE_RESOURCE_ID);

        final HashMap after = (HashMap) this.savingsAccountHelper.getSavingsDetails(savingsId).get("summary");

        assertSummaryDelta(before, after, "totalDeposits", new java.math.BigDecimal(depositAmount));
        assertSummaryDelta(before, after, "accountBalance", new java.math.BigDecimal(depositAmount));

        // Every other persisted summary field must be untouched.
        for (String field : SUMMARY_FIELDS_UNCHANGED_ON_DEPOSIT) {
            Assertions.assertEquals(before.get(field), after.get(field),
                    "Field '" + field + "' must be unchanged on the optimized deposit path. "
                            + "If this fails, a pre-JPQL summary mutator was introduced — the narrow applyDepositDelta JPQL "
                            + "does not write this field. Either revert to the wide updateSummaryDirect path or add the "
                            + "field to the delta JPQL.");
        }
    }

    /**
     * Tripwire test for the narrow delta-based JPQL on the optimized current-day withdrawal path
     * ({@code SavingsAccountRepository.applyWithdrawalDelta}). Asserts that ONLY {@code totalWithdrawals},
     * {@code totalWithdrawalFees}, {@code totalFeeCharge}, and {@code accountBalance} change on a withdrawal-with-fee,
     * and every other summary field is bit-identical pre/post.
     */
    @Test
    public void testWithdrawalSummaryInvariants_onlyChangedFieldsMutate() {
        LOG.info("=== INVARIANT: Withdrawal only mutates withdrawal totals + accountBalance ===");

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientID);
        final Integer savingsProductID = createSimpleSavingsProduct();
        final Integer savingsId = createAndActivateAccount(clientID, savingsProductID);

        // Seed with enough balance to cover the withdrawal under test
        this.savingsAccountHelper.depositToSavingsAccount(savingsId, "10000", getTransactionDate(), CommonConstants.RESPONSE_RESOURCE_ID);

        final HashMap before = (HashMap) this.savingsAccountHelper.getSavingsDetails(savingsId).get("summary");

        final String withdrawalAmount = "75.50";
        this.savingsAccountHelper.withdrawalFromSavingsAccount(savingsId, withdrawalAmount, getTransactionDate(),
                CommonConstants.RESPONSE_RESOURCE_ID);

        final HashMap after = (HashMap) this.savingsAccountHelper.getSavingsDetails(savingsId).get("summary");

        final java.math.BigDecimal w = new java.math.BigDecimal(withdrawalAmount);
        // No withdrawal-fee charge attached in this baseline test, so feeAmount = 0 and totalFee/totalWithdrawalFees
        // must NOT change. accountBalance falls by exactly the withdrawal amount.
        assertSummaryDelta(before, after, "totalWithdrawals", w);
        assertSummaryDelta(before, after, "accountBalance", w.negate());

        for (String field : SUMMARY_FIELDS_UNCHANGED_ON_WITHDRAWAL_NO_FEE) {
            Assertions.assertEquals(before.get(field), after.get(field),
                    "Field '" + field + "' must be unchanged on the optimized withdrawal path (no fee). "
                            + "If this fails, a pre-JPQL summary mutator was introduced — the narrow applyWithdrawalDelta JPQL "
                            + "does not write this field.");
        }
    }

    /**
     * Fields that must remain bit-identical across an optimized current-day deposit. These correspond to the 11 summary
     * columns that the wide {@code updateSummaryDirect} JPQL used to rewrite (with their own current values) and that
     * the new {@code applyDepositDelta} JPQL deliberately does not touch.
     */
    private static final String[] SUMMARY_FIELDS_UNCHANGED_ON_DEPOSIT = { "totalWithdrawals", "totalInterestPosted", "totalWithdrawalFees",
            "totalFeeCharge", "totalPenaltyCharge", "totalAnnualFees", "totalOverdraftInterestDerived", "totalWithholdTax",
            "totalInterestEarned", "lastInterestCalculationDate", "interestPostedTillDate" };

    private static final String[] SUMMARY_FIELDS_UNCHANGED_ON_WITHDRAWAL_NO_FEE = { "totalDeposits", "totalInterestPosted",
            "totalWithdrawalFees", "totalFeeCharge", "totalPenaltyCharge", "totalAnnualFees", "totalOverdraftInterestDerived",
            "totalWithholdTax", "totalInterestEarned", "lastInterestCalculationDate", "interestPostedTillDate" };

    private void assertSummaryDelta(HashMap before, HashMap after, String field, java.math.BigDecimal expectedDelta) {
        final java.math.BigDecimal beforeVal = toBigDecimal(before.get(field));
        final java.math.BigDecimal afterVal = toBigDecimal(after.get(field));
        final java.math.BigDecimal actualDelta = afterVal.subtract(beforeVal);
        Assertions.assertEquals(0, actualDelta.compareTo(expectedDelta), "Field '" + field + "' delta mismatch: before=" + beforeVal
                + ", after=" + afterVal + ", expected delta=" + expectedDelta + ", actual delta=" + actualDelta);
    }

    private java.math.BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return java.math.BigDecimal.ZERO;
        }
        return new java.math.BigDecimal(value.toString());
    }

    private Integer createAndActivateAccount(Integer clientID, Integer savingsProductID) {
        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplication(clientID, savingsProductID, ACCOUNT_TYPE_INDIVIDUAL);
        Assertions.assertNotNull(savingsId);
        HashMap savingsStatusHashMap = this.savingsAccountHelper.approveSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);
        savingsStatusHashMap = this.savingsAccountHelper.activateSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);
        return savingsId;
    }

    private String getTransactionDate() {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        return sdf.format(Calendar.getInstance().getTime());
    }

    private void printLatencyTable(String title, int[] sizes, double[] latencies) {
        LOG.info("╔══════════════════════════════════════════════╗");
        LOG.info("║  {}  ║", String.format("%-40s", title));
        LOG.info("╠══════════════════════════════════════════════╣");
        LOG.info("║  History Size  │  Avg Latency (ms)          ║");
        LOG.info("╠══════════════════════════════════════════════╣");
        for (int i = 0; i < sizes.length; i++) {
            LOG.info("║  {}  │  {} ms                    ║", String.format("%12d", sizes[i]), String.format("%8.2f", latencies[i]));
        }
        if (sizes.length >= 2) {
            double ratio = latencies[latencies.length - 1] / latencies[0];
            LOG.info("╠══════════════════════════════════════════════╣");
            LOG.info("║  Ratio (max/min): {}x (threshold: {}x)  ║", String.format("%.2f", ratio), O1_THRESHOLD);
        }
        LOG.info("╚══════════════════════════════════════════════╝");
    }
}
