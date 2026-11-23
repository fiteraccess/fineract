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

import static org.assertj.core.api.Assertions.within;

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
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
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
import org.junit.jupiter.api.Nested;
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

    @Nested
    class BillsReferences {

        @Test
        void freshBulkReversalRestoresBillsReferencesAndTheirJournals() {
            final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
            runAt(date, () -> {
                final String aggregator = configureAggregator();
                configureVatPayableMapping();
                final Long savingsId = createActiveCashBasedSavingsAccount(date, createSavingsAccounting());
                deposit(savingsId, date, new BigDecimal("1000"));

                postBills(savingsId, date, aggregator);

                final List<Map<String, Object>> booked = transactionRows(savingsId, 28, 29, VAT);
                assertThat(booked).hasSize(3);
                assertThat((BigDecimal) booked.get(0).get("running_balance_derived")).isEqualByComparingTo("900");
                assertThat((BigDecimal) booked.get(1).get("running_balance_derived")).isEqualByComparingTo("897");
                assertThat((BigDecimal) booked.get(2).get("running_balance_derived")).isEqualByComparingTo("895");
                assertThat(booked.get(1).get("ref_no")).isEqualTo(booked.get(0).get("ref_no"));
                assertThat(booked.get(2).get("ref_no")).isEqualTo(booked.get(0).get("ref_no"));
                assertThat(booked.get(0).get("aggregator_code")).isEqualTo(aggregator);
                assertThat(booked.get(1).get("aggregator_code")).isEqualTo(aggregator);
                assertThat(accountBalance(savingsId)).isEqualByComparingTo("895");

                reverse(savingsId, id(booked.get(0)), true, true);

                final List<Map<String, Object>> rows = transactionRows(savingsId, 28, 29, VAT);
                assertThat(rows).hasSize(6);
                assertThat(bool(rows.get(0), "is_reversed")).isTrue();
                assertThat(bool(rows.get(1), "is_reversed")).isTrue();
                assertThat(bool(rows.get(2), "is_reversed")).isTrue();
                assertThat(accountBalance(savingsId)).isEqualByComparingTo("1000");
                assertBookingEntriesLinked(id(booked.get(0)), 3);
                assertBookingEntriesLinked(id(booked.get(1)), 2);
                assertBookingEntriesLinked(id(booked.get(2)), 2);
            });
        }

        @Test
        void undoRetainsTheExistingConvenienceFeeSelection() {
            final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
            runAt(date, () -> {
                final String aggregator = configureAggregator();
                configureVatPayableMapping();
                final Long savingsId = createActiveCashBasedSavingsAccount(date, createSavingsAccounting());
                deposit(savingsId, date, new BigDecimal("1000"));
                postBills(savingsId, date, aggregator);
                final List<Map<String, Object>> booked = transactionRows(savingsId, 28, 29, VAT);

                undo(savingsId, id(booked.get(0)));

                final List<Map<String, Object>> rows = transactionRows(savingsId, 28, 29, VAT);
                assertThat(rows).hasSize(3);
                assertThat(bool(rows.get(0), "is_reversed")).isTrue();
                assertThat(bool(rows.get(1), "is_reversed")).isFalse();
                assertThat(bool(rows.get(2), "is_reversed")).isTrue();
                assertThat(accountBalance(savingsId)).isEqualByComparingTo("997");
                assertBookingEntriesLinked(id(booked.get(0)), 3);
                assertBookingEntriesLinked(id(booked.get(2)), 2);
            });
        }
    }

    private String configureAggregator() {
        final AccountHelper helper = new AccountHelper(requestSpec, responseSpec);
        final String code = Utils.uniqueRandomStringGenerator("REF_BILLS_", 8).toUpperCase();
        final Map<String, Object> body = Map.of("aggregatorPayableGlAccountId",
                helper.createLiabilityAccount("Reference Bills Payable").getAccountID(), "commissionIncomeGlAccountId",
                helper.createIncomeAccount("Reference Bills Commission").getAccountID(), "convenienceFeeIncomeGlAccountId",
                helper.createIncomeAccount("Reference Convenience Income").getAccountID(), "billsBridgeGlAccountId",
                helper.createAssetAccount("Reference Bills Bridge").getAccountID(), "active", true);
        Utils.performServerPut(requestSpec, responseSpec,
                "/fineract-provider/api/v1/aggregator-accounting-configurations/" + code + "?" + Utils.TENANT_IDENTIFIER,
                gson.toJson(body));
        return code;
    }

    private void postBills(final Long savingsId, final String date, final String aggregator) {
        final Map<String, Object> body = Map.of("locale", "en", "dateFormat", DATETIME_PATTERN, "transactionDate", date,
                "transactionAmount", new BigDecimal("100"), "paymentTypeId", 1, "aggregatorCode", aggregator, "aggregatorCommissionAmount",
                BigDecimal.ONE, "referenceTransactions",
                List.of(Map.of("type", "CONVENIENCE_FEE", "amount", new BigDecimal("3"), "description", "Bills convenience"),
                        vat("2", "Bills VAT")));
        Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions?command=withdrawal&" + Utils.TENANT_IDENTIFIER,
                gson.toJson(body), CommonConstants.RESPONSE_RESOURCE_ID);
    }

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

    /**
     * Two same-day NIP transfers on an overdraft-enabled account, where the second one's Commission and VAT legs are
     * booked into overdraft (the only way a fee leg may legitimately carry an overdraft portion). Reversing the first
     * transfer with its fees lifts those later legs out of overdraft, so the recalculation reverses and re-books them.
     * The re-booked rows used to reach the journal step unflushed (null ID, HTTP 500) and without their switch routing
     * or commission split (no journal).
     */
    @Test
    void bulkReversalRebooksLaterOverdrawnNipFeeLegsWithTheirJournals() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String switchId = uniqueSwitchId();
            final SwitchAccounting switchAccounting = configureSwitch(switchId);
            final Account vatPayable = configureVatPayableMapping();
            final Long savingsId = createActiveOverdraftAccrualSavingsAccount(date);
            deposit(savingsId, date, new BigDecimal("20.00"));

            withdraw(savingsId, date, "1.00", switchId,
                    List.of(commission("8.00", "First commission", "3.75", "4.25"), vat("0.45", "First VAT")));
            final Number firstPrincipalId = id(transactionRows(savingsId, WITHDRAWAL).get(0));
            withdraw(savingsId, date, "5.00", switchId,
                    List.of(commission("8.00", "Second commission", "3.75", "4.25"), vat("0.45", "Second VAT")));

            final List<Map<String, Object>> overdrawn = liveFeeRows(savingsId).stream()
                    .filter(row -> ((BigDecimal) row.get("overdraft_amount_derived")).signum() > 0).toList();
            assertThat(overdrawn).as("precondition: the second transfer's fee legs are booked into overdraft").hasSize(2);
            final Number overdrawnCommissionId = id(overdrawn.get(0));
            final Number overdrawnVatId = id(overdrawn.get(1));
            final Map<Long, Integer> bookingLegCounts = Map.of(overdrawnCommissionId.longValue(),
                    journalEntries(overdrawnCommissionId).size(), overdrawnVatId.longValue(), journalEntries(overdrawnVatId).size());
            final Number secondPrincipalId = id(transactionRows(savingsId, WITHDRAWAL).get(1));
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("-2.90");

            reverse(savingsId, firstPrincipalId, true, true);

            assertThat(accountBalance(savingsId)).isEqualByComparingTo("6.55");
            assertDailySnapshotMatchesLedger(savingsId);
            final List<Map<String, Object>> liveFees = liveFeeRows(savingsId);
            assertThat(liveFees).as("one live Commission and one live VAT remain for the second transfer").hasSize(2);
            final Map<String, Object> rebookedCommission = liveFees.get(0);
            final Map<String, Object> rebookedVat = liveFees.get(1);
            assertThat(((Number) rebookedCommission.get("transaction_type_enum")).intValue()).isEqualTo(COMMISSION);
            assertThat(((Number) rebookedVat.get("transaction_type_enum")).intValue()).isEqualTo(VAT);
            for (final Map<String, Object> rebooked : liveFees) {
                assertThat(id(rebooked).longValue()).as("re-booked as a new row").isNotIn(overdrawnCommissionId.longValue(),
                        overdrawnVatId.longValue());
                assertThat((BigDecimal) rebooked.get("overdraft_amount_derived")).isEqualByComparingTo("0");
                assertThat(rebooked.get("switch_id")).as("re-booked row keeps its switch routing").isEqualTo(switchId);
            }
            assertThat((BigDecimal) rebookedCommission.get("switch_fee_amount")).isEqualByComparingTo("3.75");
            assertThat((BigDecimal) rebookedCommission.get("bank_commission_amount")).isEqualByComparingTo("4.25");

            assertCreditLegs(id(rebookedCommission), 3, Map.of(switchAccounting.switchFee().getAccountID(), "3.75",
                    switchAccounting.commissionIncome().getAccountID(), "4.25"));
            assertCreditLegs(id(rebookedVat), 2, Map.of(vatPayable.getAccountID(), "0.45"));

            for (final Number replacedId : List.of(overdrawnCommissionId, overdrawnVatId)) {
                final Map<String, Object> replaced = tenantJdbc()
                        .queryForMap("SELECT is_reversed FROM m_savings_account_transaction WHERE id = ?", replacedId.longValue());
                assertThat(bool(replaced, "is_reversed")).as("overdrawn original %s is reversed", replacedId).isTrue();
                final int bookingLegs = bookingLegCounts.get(replacedId.longValue());
                final List<Map<String, Object>> entries = journalEntries(replacedId);
                assertThat(entries).as("original %s has one contra leg per booking leg", replacedId).hasSize(bookingLegs * 2);
                final Map<Integer, BigDecimal> netByAccount = new LinkedHashMap<>();
                for (final Map<String, Object> leg : entries) {
                    final BigDecimal legAmount = (BigDecimal) leg.get("amount");
                    final boolean debit = ((Number) leg.get("type_enum")).intValue() == JE_DEBIT;
                    netByAccount.merge(((Number) leg.get("gl_account_id")).intValue(), debit ? legAmount : legAmount.negate(),
                            BigDecimal::add);
                }
                netByAccount.forEach((glAccountId, net) -> assertThat(net)
                        .as("original %s nets to zero on GL account %s", replacedId, glAccountId).isEqualByComparingTo("0"));
                assertBookingEntriesLinked(replacedId, bookingLegs);
            }

            final Integer mirrorJournals = tenantJdbc().queryForObject("""
                    SELECT count(*)
                      FROM acc_gl_journal_entry je
                      JOIN m_savings_account_transaction t ON t.id = je.savings_transaction_id
                     WHERE t.savings_account_id = ? AND t.is_reversal = true
                    """, Integer.class, savingsId);
            assertThat(mirrorJournals).as("reversal mirror rows are never journaled").isZero();

            // The re-booked fee legs share the second transfer's ref_no with the rows they replaced; a fee-inclusive
            // reversal of that transfer must sweep the live legs and skip the already-reversed ones.
            reverse(savingsId, secondPrincipalId, true, true);
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("20.00");
            assertThat(liveFeeRows(savingsId)).isEmpty();
            assertDailySnapshotMatchesLedger(savingsId);
        });
    }

    /**
     * A reversal recomputes the stored account balance from the full history. A signed-statement fee is a debit that is
     * not a withdrawal type; the recompute used to leave it out and hand the fee back to the balance, which then let
     * later debits through on money the account did not have.
     */
    @Test
    void reversalRecomputeKeepsSignedStatementFeeDebited() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String switchId = uniqueSwitchId();
            configureSwitch(switchId);
            configureVatPayableMapping();
            configureSignedStatementFeeIncomeMapping();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, createSavingsAccounting());
            deposit(savingsId, date, new BigDecimal("1000.00"));

            final Map<String, Object> statementFee = new LinkedHashMap<>();
            statementFee.put("signedStatementFee", true);
            statementFee.put("locale", "en");
            statementFee.put("dateFormat", DATETIME_PATTERN);
            statementFee.put("transactionDate", date);
            statementFee.put("transactionAmount", new BigDecimal("20.00"));
            statementFee.put("paymentTypeId", 1);
            statementFee.put("referenceTransactions", List.of(vat("1.50", "Statement VAT")));
            new SavingsAccountHelper(requestSpec, responseSpec).withdrawalFromSavingsAccount(savingsId.intValue(),
                    gson.toJson(statementFee), CommonConstants.RESPONSE_RESOURCE_ID);
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("978.50");

            withdraw(savingsId, date, "100.00", switchId,
                    List.of(commission("3.00", "Commission to reverse", "1.00", "2.00"), vat("7.00", "VAT to reverse")));
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("868.50");
            final Number principalId = id(transactionRows(savingsId, WITHDRAWAL).get(0));

            reverse(savingsId, principalId, true, true);

            assertThat(accountBalance(savingsId)).as("the 20.00 signed-statement fee and its VAT stay debited after the recompute")
                    .isEqualByComparingTo("978.50");
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

    /**
     * Two consecutive months on an accrual-accounted, monthly-posting account, with a NIP transfer, a signed-statement
     * fee and a back-dated fee-inclusive reversal in the first month. At each month end the daily accruals must add up
     * to the interest posted, the posting must match an independent daily-balance calculation, interest payable must be
     * settled, and the stored balance must equal the ledger. Runs in its own office so no branch closure applies.
     */
    @Test
    void interestAndAccrualsReconcileMonthOnMonthAroundFeesAndABackdatedReversal() {
        runAt("01 December 2020", () -> {
            final SavingsAccountHelper savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
            final String switchId = uniqueSwitchId();
            configureSwitch(switchId);
            configureVatPayableMapping();
            configureSignedStatementFeeIncomeMapping();

            final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
            final Account savingsReference = accountHelper.createAssetAccount("MoM Savings Reference");
            final Account interestExpense = accountHelper.createExpenseAccount("MoM Interest Expense");
            final Account savingsControl = accountHelper.createLiabilityAccount("MoM Savings Control");
            final Account interestPayable = accountHelper.createLiabilityAccount("MoM Interest Payable");
            final Account feeIncome = accountHelper.createIncomeAccount("MoM Fee Income");
            final Account[] accountList = { savingsReference, savingsControl, interestExpense, interestPayable, feeIncome };
            final String productJson = new SavingsProductHelper().withNominalAnnualInterestRate(new BigDecimal("10.0"))
                    .withAccountingRuleAsAccrualBased(accountList).withSavingsReferenceAccountId(savingsReference.getAccountID().toString())
                    .withSavingsControlAccountId(savingsControl.getAccountID().toString())
                    .withInterestOnSavingsAccountId(interestExpense.getAccountID().toString())
                    .withInterestPayableAccountId(interestPayable.getAccountID().toString())
                    .withIncomeFromFeeAccountId(feeIncome.getAccountID().toString()).build();
            final Long productId = SavingsProductHelper.createSavingsProduct(productJson, requestSpec, responseSpec).longValue();

            final Integer officeId = new org.apache.fineract.integrationtests.common.OfficeHelper(requestSpec, responseSpec)
                    .createOffice("01 January 2020");
            final Long clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2020", officeId.toString()).longValue();
            final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, "01 December 2020")).getSavingsId();
            approveSavingsAccount(savingsId, "01 December 2020");
            activateSavingsAccount(savingsId, "01 December 2020");
            deposit(savingsId, "01 December 2020", new BigDecimal("10000.00"));

            // 10 Dec: a NIP transfer with fees, later reversed.
            moveBusinessDateTo("10 December 2020");
            withdraw(savingsId, "10 December 2020", "1000.00", switchId,
                    List.of(commission("8.00", "MoM commission", "3.75", "4.25"), vat("0.45", "MoM VAT")));
            final Number nipPrincipalId = id(transactionRows(savingsId, WITHDRAWAL).get(0));
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("8991.55");

            // 15 Dec: accruals booked for 1-14 Dec on the balances as they stand, then a signed-statement fee.
            moveBusinessDateTo("15 December 2020");
            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");
            assertThat(liveAccruals(savingsId, "2020-12-01", "2020-12-31")).as("accruals booked mid-month").isNotEmpty();
            final Map<String, Object> statementFee = new LinkedHashMap<>();
            statementFee.put("signedStatementFee", true);
            statementFee.put("locale", "en");
            statementFee.put("dateFormat", DATETIME_PATTERN);
            statementFee.put("transactionDate", "15 December 2020");
            statementFee.put("transactionAmount", new BigDecimal("20.00"));
            statementFee.put("paymentTypeId", 1);
            statementFee.put("referenceTransactions", List.of(vat("1.50", "MoM statement VAT")));
            savingsAccountHelper.withdrawalFromSavingsAccount(savingsId.intValue(), gson.toJson(statementFee),
                    CommonConstants.RESPONSE_RESOURCE_ID);
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("8970.05");
            assertDailySnapshotMatchesLedger(savingsId);

            // 21 Dec: the 10 Dec transfer is reversed with its fees, so December must accrue as if it never happened.
            moveBusinessDateTo("21 December 2020");
            reverse(savingsId, nipPrincipalId, true, true);
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("9978.50");
            // The reversal also reversed the accruals booked for 10-15 December. Until the accrual job re-books them
            // those dates have no standing transaction, so their snapshot rows must go rather than keep the old
            // balance.
            assertDailySnapshotMatchesLedger(savingsId);

            // December month end.
            moveBusinessDateTo("01 January 2021");
            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");
            savingsAccountHelper.postInterestForSavings(savingsId.intValue());

            // 14 days at 10,000.00 and 17 days at 9,978.50, at 10% over 365 days.
            final BigDecimal expectedDecember = dailyBalanceInterest(new BigDecimal("10000.00"), 14, new BigDecimal("9978.50"), 17);
            final List<Map<String, Object>> decemberPostings = livePostings(savingsId);
            assertThat(decemberPostings).as("exactly one interest posting after the first month end").hasSize(1);
            final BigDecimal decemberInterest = amount(decemberPostings.get(0));
            assertThat(decemberInterest).as("December interest posted").isCloseTo(expectedDecember, within(new BigDecimal("0.02")));
            assertThat(sum(liveAccruals(savingsId, "2020-12-01", "2020-12-31"))).as("December accruals add up to the December posting")
                    .isCloseTo(decemberInterest, within(new BigDecimal("0.02")));
            assertThat(liveAccruals(savingsId, "2020-12-01", "2020-12-31")).as("one live accrual per day of December").hasSize(31);
            final BigDecimal balanceAfterDecember = new BigDecimal("9978.50").add(decemberInterest);
            assertBalanceMatchesLedger(savingsId, balanceAfterDecember);
            // The accrual job books through the business date, so the first day of the new month is already accrued
            // and is the only interest still payable.
            final BigDecimal accruedAfterDecember = sum(liveAccruals(savingsId, "2021-01-01", "2099-12-31"));
            assertThat(netCredit(savingsId, interestPayable)).as("the December posting settles December's interest payable")
                    .isCloseTo(accruedAfterDecember, within(new BigDecimal("0.02")));
            assertThat(netCredit(savingsId, interestExpense).negate()).as("interest expense equals interest posted plus accrued")
                    .isCloseTo(decemberInterest.add(accruedAfterDecember), within(new BigDecimal("0.02")));

            // January: a deposit on the 11th, then the second month end.
            moveBusinessDateTo("11 January 2021");
            deposit(savingsId, "11 January 2021", new BigDecimal("5000.00"));
            moveBusinessDateTo("01 February 2021");
            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");
            savingsAccountHelper.postInterestForSavings(savingsId.intValue());

            // 10 days on the balance that includes December's interest, then 21 days with the deposit on top.
            final BigDecimal expectedJanuary = dailyBalanceInterest(balanceAfterDecember, 10,
                    balanceAfterDecember.add(new BigDecimal("5000.00")), 21);
            final List<Map<String, Object>> postings = livePostings(savingsId);
            assertThat(postings).as("exactly two interest postings after the second month end").hasSize(2);
            assertThat(amount(postings.get(0))).as("December posting is unchanged by the January run")
                    .isEqualByComparingTo(decemberInterest);
            final BigDecimal januaryInterest = amount(postings.get(1));
            assertThat(januaryInterest).as("January interest posted").isCloseTo(expectedJanuary, within(new BigDecimal("0.02")));
            assertThat(sum(liveAccruals(savingsId, "2021-01-01", "2021-01-31"))).as("January accruals add up to the January posting")
                    .isCloseTo(januaryInterest, within(new BigDecimal("0.02")));
            assertThat(liveAccruals(savingsId, "2021-01-01", "2021-01-31")).as("one live accrual per day of January").hasSize(31);
            final BigDecimal balanceAfterJanuary = balanceAfterDecember.add(new BigDecimal("5000.00")).add(januaryInterest);
            assertBalanceMatchesLedger(savingsId, balanceAfterJanuary);
            final BigDecimal accruedAfterJanuary = sum(liveAccruals(savingsId, "2021-02-01", "2099-12-31"));
            assertThat(netCredit(savingsId, interestPayable)).as("the January posting settles January's interest payable")
                    .isCloseTo(accruedAfterJanuary, within(new BigDecimal("0.02")));
            assertThat(netCredit(savingsId, interestExpense).negate()).as("interest expense equals interest posted plus accrued")
                    .isCloseTo(decemberInterest.add(januaryInterest).add(accruedAfterJanuary), within(new BigDecimal("0.02")));

            // Running the month end again must change nothing.
            final int accrualRowsBefore = accrualRows(savingsId).size();
            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");
            savingsAccountHelper.postInterestForSavings(savingsId.intValue());
            assertThat(accrualRows(savingsId)).as("no accrual is booked twice").hasSize(accrualRowsBefore);
            assertThat(livePostings(savingsId)).as("no interest is posted twice").hasSize(2);
            assertBalanceMatchesLedger(savingsId, balanceAfterJanuary);

            // February, posted the way it is with Synapse interest posting on: Fineract books the daily accruals and
            // the
            // month-end posting arrives from Synapse as a replay, here sent exactly as Synapse sends it.
            moveBusinessDateTo("01 March 2021");
            schedulerJobHelper.executeAndAwaitJob("Add Accrual Transactions For Savings");
            final List<Map<String, Object>> februaryAccruals = liveAccruals(savingsId, "2021-02-01", "2021-02-28");
            assertThat(februaryAccruals).as("one live accrual per day of February").hasSize(28);
            final BigDecimal februaryAccrued = sum(februaryAccruals);
            assertThat(februaryAccrued).as("February accruals match 28 days on the balance carried out of January")
                    .isCloseTo(dailyBalanceInterest(balanceAfterJanuary, 28, BigDecimal.ZERO, 0), within(new BigDecimal("0.02")));

            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(true));
            try {
                final String traceId = java.util.UUID.randomUUID().toString();
                final String replay = SavingsAccountHelper.buildReplayInterestPostingJson(februaryAccrued.toPlainString(), "01 March 2021",
                        "INTEREST_POSTING", traceId, null);
                final Integer replayedId = savingsAccountHelper.replayInterestPosting(savingsId.intValue(), replay);
                final Integer replayedAgainId = savingsAccountHelper.replayInterestPosting(savingsId.intValue(), replay);
                assertThat(replayedAgainId).as("a repeated replay returns the same posting").isEqualTo(replayedId);
            } finally {
                globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_SYNAPSE_INTEREST_POSTING,
                        new PutGlobalConfigurationsRequest().enabled(false));
            }

            final List<Map<String, Object>> allPostings = livePostings(savingsId);
            assertThat(allPostings).as("three interest postings after the third month end").hasSize(3);
            assertThat(amount(allPostings.get(2))).as("the replayed February posting").isEqualByComparingTo(februaryAccrued);
            final BigDecimal balanceAfterFebruary = balanceAfterJanuary.add(februaryAccrued);
            assertBalanceMatchesLedger(savingsId, balanceAfterFebruary);
            final BigDecimal accruedAfterFebruary = sum(liveAccruals(savingsId, "2021-03-01", "2099-12-31"));
            assertThat(netCredit(savingsId, interestPayable)).as("the replayed posting settles February's interest payable")
                    .isCloseTo(accruedAfterFebruary, within(new BigDecimal("0.02")));
            assertThat(netCredit(savingsId, interestExpense).negate()).as("interest expense equals interest posted plus accrued").isCloseTo(
                    decemberInterest.add(januaryInterest).add(februaryAccrued).add(accruedAfterFebruary), within(new BigDecimal("0.02")));

            // The daily balance snapshot must agree with the ledger on every date it holds, including the days whose
            // only row is an accrual and the days rewritten by the back-dated reversal.
            assertDailySnapshotMatchesLedger(savingsId);
        });
    }

    /** Syncs the daily balance snapshot and compares every row with the end-of-day balance summed from the ledger. */
    private void assertDailySnapshotMatchesLedger(final Long savingsId) {
        schedulerJobHelper.executeAndAwaitJob("Sync Savings Daily Balance");
        final Integer snapshotRows = tenantJdbc().queryForObject(
                "SELECT count(*) FROM m_savings_account_daily_balance WHERE savings_account_id = ?", Integer.class, savingsId);
        assertThat(snapshotRows).as("snapshot rows exist").isPositive();
        final Integer mismatches = tenantJdbc().queryForObject("""
                WITH signed AS (
                    SELECT transaction_date,
                           SUM(CASE WHEN transaction_type_enum IN (1, 3, 8) THEN amount
                                    WHEN transaction_type_enum IN (2, 4, 5, 7, 17, 18, 24, 25, 26, 27, 28, 29) THEN -amount
                                    ELSE 0 END) AS day_net
                      FROM m_savings_account_transaction
                     WHERE savings_account_id = ? AND is_reversed = false AND is_reversal = false
                     GROUP BY transaction_date
                ), eod AS (
                    SELECT transaction_date, SUM(day_net) OVER (ORDER BY transaction_date) AS balance FROM signed
                )
                SELECT count(*)
                  FROM m_savings_account_daily_balance s
                  LEFT JOIN eod e ON e.transaction_date = s.balance_date
                 WHERE s.savings_account_id = ?
                   AND (e.balance IS NULL OR s.end_of_day_balance <> e.balance)
                """, Integer.class, savingsId, savingsId);
        assertThat(mismatches).as("snapshot rows that disagree with the ledger").isZero();
    }

    private void moveBusinessDateTo(final String date) {
        businessDateHelper.updateBusinessDate(new org.apache.fineract.client.models.BusinessDateUpdateRequest()
                .type(org.apache.fineract.client.models.BusinessDateUpdateRequest.TypeEnum.BUSINESS_DATE).date(date)
                .dateFormat(DATETIME_PATTERN).locale("en"));
    }

    /** Simple daily-balance interest at 10% over a 365-day year for two consecutive balance stretches. */
    private static BigDecimal dailyBalanceInterest(final BigDecimal firstBalance, final int firstDays, final BigDecimal secondBalance,
            final int secondDays) {
        final BigDecimal balanceDays = firstBalance.multiply(BigDecimal.valueOf(firstDays))
                .add(secondBalance.multiply(BigDecimal.valueOf(secondDays)));
        return balanceDays.multiply(new BigDecimal("0.10")).divide(BigDecimal.valueOf(365), 6, java.math.RoundingMode.HALF_EVEN);
    }

    private List<Map<String, Object>> liveAccruals(final Number savingsId, final String fromIso, final String toIso) {
        return tenantJdbc().queryForList("""
                SELECT id, transaction_date, amount
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ?
                   AND transaction_type_enum = %d
                   AND is_reversed = false AND is_reversal = false
                   AND transaction_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                 ORDER BY transaction_date, id
                """.formatted(ACCRUAL), savingsId.longValue(), fromIso, toIso);
    }

    private List<Map<String, Object>> livePostings(final Number savingsId) {
        return tenantJdbc().queryForList("""
                SELECT id, transaction_date, amount
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ?
                   AND transaction_type_enum = 3
                   AND is_reversed = false AND is_reversal = false
                 ORDER BY transaction_date, id
                """, savingsId.longValue());
    }

    private BigDecimal sum(final List<Map<String, Object>> rows) {
        return rows.stream().map(this::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Credits less debits booked to the GL account by this savings account's transactions. */
    private BigDecimal netCredit(final Number savingsId, final Account glAccount) {
        return tenantJdbc().queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN je.type_enum = %d THEN je.amount ELSE -je.amount END), 0)
                  FROM acc_gl_journal_entry je
                  JOIN m_savings_account_transaction t ON t.id = je.savings_transaction_id
                 WHERE t.savings_account_id = ? AND je.account_id = ?
                """.formatted(JE_CREDIT), BigDecimal.class, savingsId.longValue(), glAccount.getAccountID());
    }

    /** The stored balance, the sum of the standing ledger rows and the expected figure must all agree. */
    private void assertBalanceMatchesLedger(final Long savingsId, final BigDecimal expected) {
        final BigDecimal ledger = tenantJdbc().queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN transaction_type_enum IN (1, 3, 8) THEN amount
                                         WHEN transaction_type_enum IN (2, 4, 5, 7, 17, 18, 24, 25, 26, 27, 28, 29) THEN -amount
                                         ELSE 0 END), 0)
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ? AND is_reversed = false AND is_reversal = false
                """, BigDecimal.class, savingsId);
        assertThat(ledger).as("ledger balance").isCloseTo(expected, within(new BigDecimal("0.02")));
        assertThat(accountBalance(savingsId)).as("stored balance equals the ledger").isEqualByComparingTo(ledger);
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

    private void configureSignedStatementFeeIncomeMapping() {
        final long financialActivityId = FinancialActivity.SIGNED_STATEMENT_FEE_INCOME.getValue();
        final FinancialActivityAccountHelper helper = new FinancialActivityAccountHelper(requestSpec);
        for (final GetFinancialActivityAccountsResponse mapping : helper.getAllFinancialActivityAccounts()) {
            if (mapping.getFinancialActivityData().getId() == financialActivityId) {
                return;
            }
        }
        final Account income = new AccountHelper(requestSpec, responseSpec).createIncomeAccount("Reversal Statement Fee Income");
        helper.createFinancialActivityAccount(new PostFinancialActivityAccountsRequest().financialActivityId(financialActivityId)
                .glAccountId(income.getAccountID().longValue()));
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

    private AccrualProduct createAccrualProduct() {
        return createAccrualProduct(null);
    }

    @SuppressWarnings("removal")
    private AccrualProduct createAccrualProduct(final String overdraftLimit) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account savingsReference = accountHelper.createAssetAccount("Accrual Reversal Savings Reference");
        final Account interestOnSavings = accountHelper.createExpenseAccount("Accrual Reversal Interest Expense");
        final Account savingsControl = accountHelper.createLiabilityAccount("Accrual Reversal Savings Control");
        final Account interestPayable = accountHelper.createLiabilityAccount("Accrual Reversal Interest Payable");
        final Account incomeFromFees = accountHelper.createIncomeAccount("Accrual Reversal Fee Income");
        final Account[] accountList = { savingsReference, savingsControl, interestOnSavings, interestPayable, incomeFromFees };
        final SavingsProductHelper productHelper = new SavingsProductHelper().withNominalAnnualInterestRate(new BigDecimal("10.0"))
                .withAccountingRuleAsAccrualBased(accountList).withSavingsReferenceAccountId(savingsReference.getAccountID().toString())
                .withSavingsControlAccountId(savingsControl.getAccountID().toString())
                .withInterestOnSavingsAccountId(interestOnSavings.getAccountID().toString())
                .withInterestPayableAccountId(interestPayable.getAccountID().toString())
                .withIncomeFromFeeAccountId(incomeFromFees.getAccountID().toString());
        if (overdraftLimit != null) {
            productHelper.withOverDraft(overdraftLimit);
        }
        final String productJson = productHelper.build();
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
        final StringBuilder sql = new StringBuilder(
                """
                        SELECT id, transaction_type_enum, amount, is_reversed, is_reversal, original_transaction_id, ref_no, running_balance_derived, aggregator_code
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

    /** Commission and VAT rows that are neither reversed nor reversal mirrors, Commission first. */
    private List<Map<String, Object>> liveFeeRows(final Number savingsId) {
        return tenantJdbc().queryForList("""
                SELECT id, transaction_type_enum, amount, overdraft_amount_derived, switch_id, switch_fee_amount, bank_commission_amount
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ?
                   AND transaction_type_enum IN (%d, %d)
                   AND is_reversed = false
                   AND is_reversal = false
                   AND ref_no = (SELECT ref_no FROM m_savings_account_transaction
                                  WHERE savings_account_id = ? AND transaction_type_enum = %d AND amount = 5
                                    AND is_reversal = false)
                 ORDER BY transaction_type_enum, id
                """.formatted(COMMISSION, VAT, WITHDRAWAL), savingsId.longValue(), savingsId.longValue());
    }

    /** Asserts the transaction booked exactly the expected number of journal legs and the given credit legs. */
    private void assertCreditLegs(final Number savingsTransactionId, final int totalLegs, final Map<Integer, String> expectedCredits) {
        final List<Map<String, Object>> entries = journalEntries(savingsTransactionId);
        assertThat(entries).as("journal legs of re-booked transaction %s", savingsTransactionId).hasSize(totalLegs);
        final Map<Integer, BigDecimal> credits = new LinkedHashMap<>();
        for (final Map<String, Object> leg : entries) {
            if (((Number) leg.get("type_enum")).intValue() == JE_CREDIT) {
                credits.put(((Number) leg.get("gl_account_id")).intValue(), (BigDecimal) leg.get("amount"));
            }
        }
        Assertions.assertThat(credits.keySet()).containsExactlyInAnyOrderElementsOf(expectedCredits.keySet());
        expectedCredits.forEach((glAccountId, expected) -> assertThat(credits.get(glAccountId)).isEqualByComparingTo(expected));
    }

    private Long createActiveOverdraftAccrualSavingsAccount(final String date) {
        final Long clientId = createClient();
        final Long productId = createAccrualProduct("100").productId().longValue();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
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
