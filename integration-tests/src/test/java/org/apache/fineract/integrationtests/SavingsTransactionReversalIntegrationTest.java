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

import com.google.gson.Gson;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.client.models.GetFinancialActivityAccountsResponse;
import org.apache.fineract.client.models.PostFinancialActivityAccountsRequest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.FinancialActivityAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.apache.fineract.integrationtests.support.TenantJdbcSupport;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of savings transaction reversal: fee-inclusive bulk sweeps (Commission/VAT), EMT levy siblings,
 * the persisted commission allocation on the reversal journal, journal-entry reversed/reversal_id linkage, transfer
 * reversal via the account-transfer command, and accrual reverse-and-rebook on accrual-based products.
 */
@SuppressWarnings("removal")
class SavingsTransactionReversalIntegrationTest extends BaseSavingsIntegrationTest {

    private static final int WITHDRAWAL = 2;
    private static final int EMT_LEVY = 24;
    private static final int COMMISSION = 25;
    private static final int VAT = 26;
    private static final int ACCRUAL = 10;
    private static final int JE_CREDIT = 1;
    private static final int JE_DEBIT = 2;

    private final Gson gson = new Gson();

    @Test
    void bulkReversalWithIncludeFeesReversesPrincipalCommissionAndVat() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String switchId = uniqueSwitchId();
            final SwitchAccounting switchAccounting = configureSwitch(switchId);
            final Account vatPayable = configureVatPayableMapping();
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));

            withdraw(savingsId, date, "100.00", switchId,
                    List.of(commission("3.00", "Commission to reverse", "1.00", "2.00"), vat("7.00", "VAT to reverse")));
            final List<Map<String, Object>> booked = transactionRows(savingsId, WITHDRAWAL, COMMISSION, VAT);
            final Number principalId = id(booked.get(0));
            final Number commissionId = id(booked.get(1));
            final Number vatId = id(booked.get(2));

            reverse(savingsId, principalId, true, true);

            final List<Map<String, Object>> rows = transactionRows(savingsId, WITHDRAWAL, COMMISSION, VAT);
            assertThat(rows).hasSize(6);
            rows.subList(0, 3).forEach(row -> assertThat(bool(row, "is_reversed")).isTrue());
            final List<Map<String, Object>> mirrors = rows.subList(3, 6);
            for (final Map<String, Object> mirror : mirrors) {
                assertThat(bool(mirror, "is_reversal")).isTrue();
                assertThat(mirror.get("original_transaction_id")).isNotNull();
            }
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("1000.00");

            assertReversalJournal(principalId, 1, Map.of(key(JE_DEBIT, switchAccounting.payable()), "100.00",
                    key(JE_CREDIT, savingsAccounting.savingsControl()), "100.00"));
            assertReversalJournal(commissionId, 2,
                    Map.of(key(JE_DEBIT, switchAccounting.switchFee()), "1.00", key(JE_DEBIT, switchAccounting.commissionIncome()), "2.00",
                            key(JE_CREDIT, savingsAccounting.savingsControl()), "3.00"));
            assertReversalJournal(vatId, 1,
                    Map.of(key(JE_DEBIT, vatPayable), "7.00", key(JE_CREDIT, savingsAccounting.savingsControl()), "7.00"));
        });
    }

    @Test
    void bulkReversalWithoutIncludeFeesLeavesNipFeesStanding() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String switchId = uniqueSwitchId();
            configureSwitch(switchId);
            configureVatPayableMapping();
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));

            withdraw(savingsId, date, "100.00", switchId,
                    List.of(commission("3.00", "Commission stands", "1.00", "2.00"), vat("7.00", "VAT stands")));
            final Number principalId = id(transactionRows(savingsId, WITHDRAWAL).get(0));

            reverse(savingsId, principalId, true, false);

            final List<Map<String, Object>> rows = transactionRows(savingsId, WITHDRAWAL, COMMISSION, VAT);
            assertThat(bool(rows.get(0), "is_reversed")).isTrue();
            assertThat(bool(rows.get(1), "is_reversed")).as("commission must stand without includeFees").isFalse();
            assertThat(bool(rows.get(2), "is_reversed")).as("vat must stand without includeFees").isFalse();
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("990.00");
        });
    }

    @Test
    void bulkReversalSweepsEmtLevyWithPrincipal() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Account emtLevyAccount = configureEmtLevyMapping();
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("100.00"));

            withdraw(savingsId, date, "25.00", null, List.of(emtLevy("5.00")));
            final List<Map<String, Object>> booked = transactionRows(savingsId, WITHDRAWAL, EMT_LEVY);
            final Number principalId = id(booked.get(0));
            final Number emtLevyId = id(booked.get(1));

            reverse(savingsId, principalId, true, false);

            final List<Map<String, Object>> rows = transactionRows(savingsId, WITHDRAWAL, EMT_LEVY);
            assertThat(rows).hasSize(4);
            assertThat(bool(rows.get(0), "is_reversed")).isTrue();
            assertThat(bool(rows.get(1), "is_reversed")).isTrue();
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("100.00");

            assertReversalJournal(emtLevyId, 1,
                    Map.of(key(JE_DEBIT, emtLevyAccount), "5.00", key(JE_CREDIT, savingsAccounting.savingsControl()), "5.00"));
        });
    }

    @Test
    void explicitCommissionReversalProducesBalancedTwoLegMirror() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String switchId = uniqueSwitchId();
            final SwitchAccounting switchAccounting = configureSwitch(switchId);
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));

            withdraw(savingsId, date, "100.00", switchId, List.of(commission("3.00", "Direct commission reversal", "1.00", "2.00")));
            final Number commissionId = id(transactionRows(savingsId, COMMISSION).get(0));

            reverse(savingsId, commissionId, false, false);

            assertThat(bool(transactionRows(savingsId, COMMISSION).get(0), "is_reversed")).isTrue();
            assertReversalJournal(commissionId, 2,
                    Map.of(key(JE_DEBIT, switchAccounting.switchFee()), "1.00", key(JE_DEBIT, switchAccounting.commissionIncome()), "2.00",
                            key(JE_CREDIT, savingsAccounting.savingsControl()), "3.00"));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void secondReversalOfTheSameTransactionSurfacesTheAlreadyReversedGuard() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("100.00"));
            final Number depositId = id(transactionRows(savingsId).get(0));
            reverse(savingsId, depositId, false, false);

            final List<Map<String, Object>> errors = (List<Map<String, Object>>) reverseExpectingError(savingsId, depositId, 404);

            assertThat(errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE))
                    .isEqualTo("error.msg.saving.account.trasaction.id.invalid");
        });
    }

    @Test
    void reversalOnAccrualProductReversesAndRebooksAccrualsOnRevisedBalances() {
        runAt("12 August 2021", () -> {
            final SavingsAccountHelper savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
            final AccrualProduct product = createAccrualProduct();
            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2020");
            final Integer savingsAccountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, product.productId(),
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, "02 August 2021");
            savingsAccountHelper.approveSavingsOnDate(savingsAccountId, "02 August 2021");
            savingsAccountHelper.activateSavings(savingsAccountId, "02 August 2021");
            savingsAccountHelper.depositToSavingsAccount(savingsAccountId, "1000", "02 August 2021", CommonConstants.RESPONSE_RESOURCE_ID);
            final Integer laterDepositId = (Integer) savingsAccountHelper.depositToSavingsAccount(savingsAccountId, "1000",
                    "09 August 2021", CommonConstants.RESPONSE_RESOURCE_ID);

            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");

            final List<Map<String, Object>> bookedAccruals = accrualRows(savingsAccountId);
            assertThat(bookedAccruals).isNotEmpty();
            final BigDecimal singleBalanceDailyAccrual = amount(bookedAccruals.get(0));
            final List<Map<String, Object>> affectedAccruals = bookedAccruals.stream()
                    .filter(row -> !date(row).isBefore(java.time.LocalDate.of(2021, 8, 9))).toList();
            assertThat(affectedAccruals).as("accruals on the doubled balance must exist before reversal").isNotEmpty();
            affectedAccruals.forEach(row -> assertThat(amount(row)).isGreaterThan(singleBalanceDailyAccrual));

            reverse(savingsAccountId.longValue(), laterDepositId, false, false);

            for (final Map<String, Object> row : accrualRows(savingsAccountId)) {
                final boolean affected = !date(row).isBefore(java.time.LocalDate.of(2021, 8, 9));
                assertThat(bool(row, "is_reversed")).as("accrual on %s", date(row)).isEqualTo(affected);
            }
            final Number reversedAccrualId = id(affectedAccruals.get(0));
            assertBookingEntriesLinked(reversedAccrualId, 2);

            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");

            final List<Map<String, Object>> rebooked = accrualRows(savingsAccountId).stream()
                    .filter(row -> !bool(row, "is_reversed") && !date(row).isBefore(java.time.LocalDate.of(2021, 8, 9))).toList();
            assertThat(rebooked).as("affected dates must be re-booked after the job re-runs").hasSameSizeAs(affectedAccruals);
            rebooked.forEach(row -> assertThat(amount(row)).isEqualByComparingTo(singleBalanceDailyAccrual));
        });
    }

    // ---------- undo (flag-only) semantics ----------

    @Test
    void undoIsFlagOnlyAndRestoresBalance() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));
            withdraw(savingsId, date, "100.00", null, List.of());
            final Number withdrawalId = id(transactionRows(savingsId, WITHDRAWAL).get(0));

            undo(savingsId, withdrawalId);

            final List<Map<String, Object>> rows = transactionRows(savingsId, WITHDRAWAL);
            assertThat(rows).as("undo must not create a mirror row").hasSize(1);
            assertThat(bool(rows.get(0), "is_reversed")).isTrue();
            assertThat(bool(rows.get(0), "is_reversal")).isFalse();
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("1000.00");

            assertBookingEntriesLinked(withdrawalId, 2);
        });
    }

    @Test
    void undoNipWithdrawalSweepsCommissionAndVatSiblings() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String switchId = uniqueSwitchId();
            final SwitchAccounting switchAccounting = configureSwitch(switchId);
            final Account vatPayable = configureVatPayableMapping();
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));

            withdraw(savingsId, date, "100.00", switchId,
                    List.of(commission("3.00", "Commission swept on undo", "1.00", "2.00"), vat("7.00", "VAT swept on undo")));
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("890.00");
            final List<Map<String, Object>> booked = transactionRows(savingsId, WITHDRAWAL, COMMISSION, VAT);
            final Number withdrawalId = id(booked.get(0));
            final Number commissionId = id(booked.get(1));
            final Number vatId = id(booked.get(2));

            undo(savingsId, withdrawalId);

            final List<Map<String, Object>> rows = transactionRows(savingsId, WITHDRAWAL, COMMISSION, VAT);
            assertThat(rows).as("undo must not create mirror rows").hasSize(3);
            rows.forEach(row -> assertThat(bool(row, "is_reversed")).as("row %s swept by undo", id(row)).isTrue());
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("1000.00");

            assertReversalJournal(withdrawalId, 1, Map.of(key(JE_DEBIT, switchAccounting.payable()), "100.00",
                    key(JE_CREDIT, savingsAccounting.savingsControl()), "100.00"));
            assertReversalJournal(commissionId, 2,
                    Map.of(key(JE_DEBIT, switchAccounting.switchFee()), "1.00", key(JE_DEBIT, switchAccounting.commissionIncome()), "2.00",
                            key(JE_CREDIT, savingsAccounting.savingsControl()), "3.00"));
            assertReversalJournal(vatId, 1,
                    Map.of(key(JE_DEBIT, vatPayable), "7.00", key(JE_CREDIT, savingsAccounting.savingsControl()), "7.00"));
        });
    }

    @Test
    void undoWithdrawalSweepsEmtLevySibling() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            configureEmtLevyMapping();
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("100.00"));

            withdraw(savingsId, date, "25.00", null, List.of(emtLevy("5.00")));
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("70.00");
            final List<Map<String, Object>> booked = transactionRows(savingsId, WITHDRAWAL, EMT_LEVY);
            final Number withdrawalId = id(booked.get(0));
            final Number emtLevyId = id(booked.get(1));

            undo(savingsId, withdrawalId);

            final List<Map<String, Object>> rows = transactionRows(savingsId, WITHDRAWAL, EMT_LEVY);
            assertThat(rows).as("undo must not create mirror rows").hasSize(2);
            assertThat(bool(rows.get(0), "is_reversed")).as("withdrawal undone").isTrue();
            assertThat(bool(rows.get(1), "is_reversed")).as("EMT levy swept by undo").isTrue();
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("100.00");

            assertBookingEntriesLinked(emtLevyId, 2);
        });
    }

    @Test
    void undoingAnAlreadyUndoneTransactionSucceedsSilently() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));
            withdraw(savingsId, date, "100.00", null, List.of());
            final Number withdrawalId = id(transactionRows(savingsId, WITHDRAWAL).get(0));

            undo(savingsId, withdrawalId);
            // Divergence from command=reverse (404 already-reversed guard): a second undo silently succeeds.
            // Pinned here so an upstream behavior change surfaces; Synapse never proxies undo (AB-412 §3.4).
            undo(savingsId, withdrawalId);

            assertThat(transactionRows(savingsId, WITHDRAWAL)).hasSize(1);
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("1000.00");
        });
    }

    private void undo(final Long savingsId, final Number transactionId) {
        Utils.performServerPost(requestSpec, responseSpec, undoUrl(savingsId, transactionId),
                undoBody(dateTimeFormatter.format(Utils.getLocalDateOfTenant())), CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private String undoUrl(final Long savingsId, final Number transactionId) {
        return "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions/" + transactionId.longValue() + "?command=undo&"
                + Utils.TENANT_IDENTIFIER;
    }

    private String undoBody(final String date) {
        return gson.toJson(Map.of("transactionDate", date, "transactionAmount", "0", "dateFormat", DATETIME_PATTERN, "locale", "en"));
    }

    // ---------- reversal plumbing ----------

    private void reverse(final Long savingsId, final Number transactionId, final boolean isBulk, final boolean includeFees) {
        Utils.performServerPost(requestSpec, responseSpec, reverseUrl(savingsId, transactionId),
                gson.toJson(Map.of("isBulk", isBulk, "includeFees", includeFees)), CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private Object reverseExpectingError(final Long savingsId, final Number transactionId, final int statusCode) {
        final ResponseSpecification errorSpec = new ResponseSpecBuilder().expectStatusCode(statusCode).build();
        return Utils.performServerPost(requestSpec, errorSpec, reverseUrl(savingsId, transactionId), "{}", CommonConstants.RESPONSE_ERROR);
    }

    private String reverseUrl(final Long savingsId, final Number transactionId) {
        return "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions/" + transactionId.longValue() + "?command=reverse&"
                + Utils.TENANT_IDENTIFIER;
    }

    // ---------- journal assertions ----------

    /**
     * Asserts the reversal legs (flipped sides, expected accounts/amounts) exist and that every booking leg is flagged
     * reversed with reversal_id pointing at a reversal leg — the exact shape the GL-balance enquiry's exclude-reversed
     * predicate requires.
     */
    private void assertReversalJournal(final Number savingsTransactionId, final int creditLegsOnBooking,
            final Map<String, String> expectedReversalPostings) {
        final int legsPerSide = 1 + creditLegsOnBooking;
        final List<Map<String, Object>> entries = journalEntries(savingsTransactionId);
        assertThat(entries).hasSize(legsPerSide * 2);
        final List<Map<String, Object>> reversalLegs = entries.subList(legsPerSide, entries.size());
        for (final Map<String, Object> leg : reversalLegs) {
            final String legKey = key(((Number) leg.get("type_enum")).intValue(), ((Number) leg.get("gl_account_id")).intValue());
            Assertions.assertThat(expectedReversalPostings).containsKey(legKey);
            assertThat((BigDecimal) leg.get("amount")).isEqualByComparingTo(expectedReversalPostings.get(legKey));
        }
        assertBookingEntriesLinked(savingsTransactionId, legsPerSide);
    }

    private void assertBookingEntriesLinked(final Number savingsTransactionId, final int legsPerSide) {
        final List<Map<String, Object>> entries = journalEntries(savingsTransactionId);
        assertThat(entries).hasSize(legsPerSide * 2);
        final List<Map<String, Object>> booking = entries.subList(0, legsPerSide);
        final List<Map<String, Object>> reversal = entries.subList(legsPerSide, entries.size());
        final Set<Long> reversalEntryIds = new HashSet<>();
        for (final Map<String, Object> leg : reversal) {
            assertThat(bool(leg, "reversed")).as("reversal leg stays unreversed").isFalse();
            reversalEntryIds.add(((Number) leg.get("id")).longValue());
        }
        for (final Map<String, Object> leg : booking) {
            assertThat(bool(leg, "reversed")).as("booking leg is flagged reversed").isTrue();
            assertThat(leg.get("reversal_id")).isNotNull();
            Assertions.assertThat(reversalEntryIds).contains(((Number) leg.get("reversal_id")).longValue());
        }
    }

    private String key(final int entryType, final Account account) {
        return key(entryType, account.getAccountID());
    }

    private String key(final int entryType, final int glAccountId) {
        return entryType + ":" + glAccountId;
    }

    // ---------- fixtures ----------

    private record SwitchAccounting(Account payable, Account switchFee, Account commissionIncome) {
    }

    private record SavingsAccounting(Account savingsReference, Account savingsControl, Account feeIncome, Account interestExpense) {

        Account[] productAccounts() {
            return new Account[] { savingsReference, savingsControl, feeIncome, interestExpense };
        }
    }

    private record AccrualProduct(Integer productId) {
    }

    private String uniqueSwitchId() {
        return ("NIPREV" + System.nanoTime());
    }

    private SwitchAccounting configureSwitch(final String switchId) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account payable = accountHelper.createLiabilityAccount("Reversal NIP Payable");
        final Account switchFee = accountHelper.createExpenseAccount("Reversal NIP Switch Fee");
        final Account commissionIncome = accountHelper.createIncomeAccount("Reversal NIP Commission Income");
        final Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("switchPayableGlAccountId", payable.getAccountID());
        configuration.put("switchFeeGlAccountId", switchFee.getAccountID());
        configuration.put("commissionIncomeGlAccountId", commissionIncome.getAccountID());
        configuration.put("direction", "OUTBOUND");
        configuration.put("active", true);
        Utils.performServerPut(requestSpec, responseSpec,
                "/fineract-provider/api/v1/nip-switch-accounting-configurations/" + switchId + "?" + Utils.TENANT_IDENTIFIER,
                gson.toJson(configuration));
        return new SwitchAccounting(payable, switchFee, commissionIncome);
    }

    private Account configureVatPayableMapping() {
        return configureFinancialActivityMapping(FinancialActivity.VAT_PAYABLE.getValue(), "Reversal VAT Payable");
    }

    private Account configureEmtLevyMapping() {
        return configureFinancialActivityMapping(FinancialActivity.EMT_LEVY.getValue(), "Reversal EMT Levy Payable");
    }

    private Account configureFinancialActivityMapping(final long financialActivityId, final String accountName) {
        final FinancialActivityAccountHelper helper = new FinancialActivityAccountHelper(requestSpec);
        for (final GetFinancialActivityAccountsResponse mapping : helper.getAllFinancialActivityAccounts()) {
            if (mapping.getFinancialActivityData().getId() == financialActivityId) {
                return new Account(mapping.getGlAccountData().getId().intValue(), Account.AccountType.LIABILITY);
            }
        }
        final Account account = new AccountHelper(requestSpec, responseSpec).createLiabilityAccount(accountName);
        helper.createFinancialActivityAccount(new PostFinancialActivityAccountsRequest().financialActivityId(financialActivityId)
                .glAccountId(account.getAccountID().longValue()));
        return account;
    }

    private SavingsAccounting createSavingsAccounting() {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        return new SavingsAccounting(accountHelper.createAssetAccount("Reversal Savings Reference"),
                accountHelper.createLiabilityAccount("Reversal Savings Control"), accountHelper.createIncomeAccount("Reversal Fee Income"),
                accountHelper.createExpenseAccount("Reversal Interest Expense"));
    }

    @SuppressWarnings("removal")
    private Long createActiveCashBasedSavingsAccount(final String date, final SavingsAccounting accounting) {
        final Long clientId = createClient();
        final String productJson = new SavingsProductHelper().withCurrencyCode("USD").withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance()
                .withAccountingRuleAsCashBased(accounting.productAccounts()).build();
        final Long productId = SavingsProductHelper.createSavingsProduct(productJson, requestSpec, responseSpec).longValue();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
    }

    @SuppressWarnings("removal")
    private AccrualProduct createAccrualProduct() {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account savingsReference = accountHelper.createAssetAccount("Accrual Reversal Savings Reference");
        final Account interestOnSavings = accountHelper.createExpenseAccount("Accrual Reversal Interest Expense");
        final Account savingsControl = accountHelper.createLiabilityAccount("Accrual Reversal Savings Control");
        final Account interestPayable = accountHelper.createLiabilityAccount("Accrual Reversal Interest Payable");
        final Account incomeFromFees = accountHelper.createIncomeAccount("Accrual Reversal Fee Income");
        final Account[] accountList = { savingsReference, savingsControl, interestOnSavings, interestPayable, incomeFromFees };
        final String productJson = new SavingsProductHelper().withNominalAnnualInterestRate(new BigDecimal("10.0"))
                .withAccountingRuleAsAccrualBased(accountList).withSavingsReferenceAccountId(savingsReference.getAccountID().toString())
                .withSavingsControlAccountId(savingsControl.getAccountID().toString())
                .withInterestOnSavingsAccountId(interestOnSavings.getAccountID().toString())
                .withInterestPayableAccountId(interestPayable.getAccountID().toString())
                .withIncomeFromFeeAccountId(incomeFromFees.getAccountID().toString()).build();
        return new AccrualProduct(SavingsProductHelper.createSavingsProduct(productJson, requestSpec, responseSpec));
    }

    private Long createClient() {
        return clientHelper.createClient(ClientHelper.defaultClientCreationRequest().dateFormat("yyyy-MM-dd").activationDate("2011-03-04"))
                .getClientId();
    }

    private void withdraw(final Long savingsId, final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("locale", "en");
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("transactionDate", date);
        request.put("transactionAmount", new BigDecimal(amount));
        request.put("paymentTypeId", 1);
        if (switchId != null) {
            request.put("switchId", switchId);
        }
        request.put("referenceTransactions", references);
        new SavingsAccountHelper(requestSpec, responseSpec).withdrawalFromSavingsAccount(savingsId.intValue(), gson.toJson(request),
                CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private Map<String, Object> commission(final String amount, final String description, final String switchFee,
            final String bankCommission) {
        final Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "COMMISSION");
        reference.put("amount", new BigDecimal(amount));
        reference.put("description", description);
        reference.put("breakdown", Map.of("switchFee", Map.of("amount", new BigDecimal(switchFee)), "bankCommission",
                Map.of("amount", new BigDecimal(bankCommission))));
        return reference;
    }

    private Map<String, Object> vat(final String amount, final String description) {
        final Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "VAT");
        reference.put("amount", new BigDecimal(amount));
        reference.put("description", description);
        return reference;
    }

    private Map<String, Object> emtLevy(final String amount) {
        return Map.of("type", "EMT_LEVY", "amount", new BigDecimal(amount));
    }

    // ---------- row readers ----------

    private List<Map<String, Object>> transactionRows(final Number savingsId, final int... types) {
        final StringBuilder sql = new StringBuilder("""
                SELECT id, transaction_type_enum, amount, is_reversed, is_reversal, original_transaction_id, ref_no
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ?
                """);
        if (types.length > 0) {
            sql.append(" AND transaction_type_enum IN (");
            for (int i = 0; i < types.length; i++) {
                sql.append(i == 0 ? "" : ", ").append(types[i]);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY is_reversal, id");
        return tenantJdbc().queryForList(sql.toString(), savingsId.longValue());
    }

    private List<Map<String, Object>> accrualRows(final Number savingsId) {
        return tenantJdbc().queryForList("""
                SELECT id, transaction_date, amount, is_reversed
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ?
                   AND transaction_type_enum = %d
                 ORDER BY transaction_date, id
                """.formatted(ACCRUAL), savingsId.longValue());
    }

    private List<Map<String, Object>> journalEntries(final Number savingsTransactionId) {
        return tenantJdbc().queryForList("""
                SELECT id, type_enum, account_id AS gl_account_id, amount, reversed, reversal_id
                  FROM acc_gl_journal_entry
                 WHERE savings_transaction_id = ?
                 ORDER BY id
                """, savingsTransactionId.longValue());
    }

    private BigDecimal accountBalance(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT account_balance_derived FROM m_savings_account WHERE id = ?", BigDecimal.class,
                savingsId);
    }

    private Number id(final Map<String, Object> row) {
        return (Number) row.get("id");
    }

    private BigDecimal amount(final Map<String, Object> row) {
        return (BigDecimal) row.get("amount");
    }

    private java.time.LocalDate date(final Map<String, Object> row) {
        return ((java.sql.Date) row.get("transaction_date")).toLocalDate();
    }

    private boolean bool(final Map<String, Object> row, final String column) {
        return Boolean.TRUE.equals(row.get(column));
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }
}
