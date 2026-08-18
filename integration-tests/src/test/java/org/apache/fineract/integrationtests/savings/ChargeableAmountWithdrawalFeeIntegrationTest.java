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
package org.apache.fineract.integrationtests.savings;

import com.google.gson.Gson;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.client.models.ChargeRequest;
import org.apache.fineract.client.models.PostChargesResponse;
import org.apache.fineract.client.models.PostSavingsAccountsResponse;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesRequest;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.PostSavingsProductsResponse;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.apache.fineract.integrationtests.support.TenantJdbcSupport;
import org.junit.jupiter.api.Test;

/**
 * AB-243: {@code chargeableAmount} on the withdrawal command — the base withdrawal fees are computed on. Absent = fees
 * on the transaction amount as always; 0 = no withdrawal charges at all; positive = flat fees unchanged, percentage
 * fees computed on the base. Fee rows keep the withdrawal's {@code ref_no}, so undo/reverse sweep them unchanged.
 */
@SuppressWarnings("removal")
class ChargeableAmountWithdrawalFeeIntegrationTest extends BaseSavingsIntegrationTest {

    private static final int WITHDRAWAL = 2;
    private static final int WITHDRAWAL_FEE = 4;
    private static final int FLAT = 1;
    private static final int PERCENT_OF_AMOUNT = 2;
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("30000.00");

    private final Gson gson = new Gson();

    @Test
    void feeChargesOnTransactionAmountWhenChargeableAmountAbsent() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, FLAT, 100.0);

            withdraw(savingsId, date, "5000.00", null);

            assertThat(accountBalance(savingsId)).isEqualByComparingTo("24900.00");
            final List<BigDecimal> fees = feeAmounts(savingsId);
            assertThat(fees).hasSize(1);
            assertThat(fees.get(0)).isEqualByComparingTo("100");
            assertThat(chargeableAmountOf(savingsId, WITHDRAWAL)).as("no base supplied — nothing recorded").isNull();
        });
    }

    @Test
    void zeroChargeableAmountSkipsWithdrawalFeesEntirely() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, FLAT, 100.0);

            withdraw(savingsId, date, "5500.00", "0");

            assertThat(accountBalance(savingsId)).isEqualByComparingTo("24500.00");
            assertThat(feeAmounts(savingsId)).isEmpty();
            assertThat(chargeableAmountOf(savingsId, WITHDRAWAL)).as("fee exemption recorded for back office").isEqualByComparingTo("0");
        });
    }

    @Test
    void percentageFeeIsComputedOnChargeableAmountNotTransactionAmount() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, PERCENT_OF_AMOUNT, 10.0);

            // 5500 withdrawn, but only 5000 is fee-bearing: 10% of 5000 = 500, not 550
            final Number withdrawalId = withdraw(savingsId, date, "5500.00", "5000.00");

            assertThat(accountBalance(savingsId)).isEqualByComparingTo("24000.00");
            final List<BigDecimal> fees = feeAmounts(savingsId);
            assertThat(fees).hasSize(1);
            assertThat(fees.get(0)).isEqualByComparingTo("500");
            assertThat(chargeableAmountOf(savingsId, WITHDRAWAL)).isEqualByComparingTo("5000.00");
            assertThat(chargeableAmountOf(savingsId, WITHDRAWAL_FEE)).as("base is a property of the principal row only").isNull();

            // running balances step down progressively: principal first, then the fee
            assertThat(runningBalanceOf(savingsId, WITHDRAWAL)).isEqualByComparingTo("24500.00");
            assertThat(runningBalanceOf(savingsId, WITHDRAWAL_FEE)).isEqualByComparingTo("24000.00");

            // the base is exposed on the transaction read so back office can explain the fee
            final Float apiChargeableAmount = Utils.performServerGet(requestSpec, responseSpec, "/fineract-provider/api/v1/savingsaccounts/"
                    + savingsId + "/transactions/" + withdrawalId.longValue() + "?" + Utils.TENANT_IDENTIFIER, "chargeableAmount");
            assertThat(apiChargeableAmount).isEqualTo(5000.0f);
        });
    }

    @Test
    void flatFeeIsUnchangedByChargeableAmount() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, FLAT, 100.0);

            withdraw(savingsId, date, "5500.00", "5000.00");

            assertThat(accountBalance(savingsId)).isEqualByComparingTo("24400.00");
            final List<BigDecimal> fees = feeAmounts(savingsId);
            assertThat(fees).hasSize(1);
            assertThat(fees.get(0)).isEqualByComparingTo("100");
        });
    }

    @Test
    void undoSweepsTheBaseComputedFeeWithThePrincipal() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, PERCENT_OF_AMOUNT, 10.0);
            final Number withdrawalId = withdraw(savingsId, date, "5500.00", "5000.00");
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("24000.00");

            undo(savingsId, withdrawalId, date);

            assertThat(accountBalance(savingsId)).isEqualByComparingTo(OPENING_BALANCE);
            transactionRows(savingsId, WITHDRAWAL, WITHDRAWAL_FEE)
                    .forEach(row -> assertThat(bool(row, "is_reversed")).as("row %s swept by undo", row.get("id")).isTrue());
        });
    }

    @Test
    void reverseSweepsTheBaseComputedFeeWithThePrincipal() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, PERCENT_OF_AMOUNT, 10.0);
            final Number withdrawalId = withdraw(savingsId, date, "5500.00", "5000.00");

            reverse(savingsId, withdrawalId);

            assertThat(accountBalance(savingsId)).isEqualByComparingTo(OPENING_BALANCE);
            transactionRows(savingsId, WITHDRAWAL, WITHDRAWAL_FEE).stream().filter(row -> !bool(row, "is_reversal"))
                    .forEach(row -> assertThat(bool(row, "is_reversed")).as("row %s swept by reverse", row.get("id")).isTrue());
        });
    }

    @Test
    void negativeChargeableAmountIsRejected() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long savingsId = activeAccountWithWithdrawalFee(date, FLAT, 100.0);

            final ResponseSpecification badRequest = new ResponseSpecBuilder().expectStatusCode(400).build();
            Utils.performServerPost(requestSpec, badRequest, withdrawalUrl(savingsId), withdrawalBody(date, "5000.00", "-1"),
                    CommonConstants.RESPONSE_ERROR);

            assertThat(accountBalance(savingsId)).isEqualByComparingTo(OPENING_BALANCE);
        });
    }

    // ---------- fixtures ----------

    private Long activeAccountWithWithdrawalFee(final String date, final int chargeCalculationType, final double chargeAmount) {
        // ISO dates: the default fixture pairs an ISO dateOfBirth with "dd MMMM yyyy", which the strict
        // date parser rejects — same fix as NipWithdrawalBundleIntegrationTest.
        final Long clientId = clientHelper
                .createClient(ClientHelper.defaultClientCreationRequest().dateFormat("yyyy-MM-dd").activationDate("2011-03-04"))
                .getClientId();
        final PostSavingsProductsResponse product = createProduct(savingsProduct());
        final PostSavingsAccountsResponse savings = applySavingsAccount(applySavingsRequest(clientId, product.getResourceId(), date));
        final Long savingsId = savings.getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        deposit(savingsId, date, OPENING_BALANCE);

        final PostChargesResponse charge = new ChargesHelper().createCharges(new ChargeRequest().active(true)
                .name(Utils.uniqueRandomStringGenerator("Charge_Savings_", 6)).currencyCode("USD").amount(chargeAmount).chargeAppliesTo(2)
                .chargeTimeType(5).chargeCalculationType(chargeCalculationType).locale("en"));
        ok(fineractClient().savingsAccountCharges.addSavingsAccountCharge(savingsId, new PostSavingsAccountsSavingsAccountIdChargesRequest()
                .chargeId(charge.getResourceId()).amount((float) chargeAmount).locale("en")));
        return savingsId;
    }

    private PostSavingsProductsRequest savingsProduct() {
        return new PostSavingsProductsRequest().locale("en").name(Utils.uniqueRandomStringGenerator("SAVINGS_PRODUCT_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Savings product for chargeableAmount fee test")
                .currencyCode("USD").digitsAfterDecimal(2).inMultiplesOf(0).nominalAnnualInterestRate(10.0)
                .interestCompoundingPeriodType(InterestPeriodType.DAILY).interestPostingPeriodType(InterestPeriodType.MONTHLY)
                .interestCalculationType(InterestCalculationType.DAILY_BALANCE).interestCalculationDaysInYearType(DaysInYearType.DAYS_365)
                .accountingRule(1).allowOverdraft(false).enforceMinRequiredBalance(false).withHoldTax(false)
                .isDormancyTrackingActive(false);
    }

    // ---------- command plumbing ----------

    private Number withdraw(final Long savingsId, final String date, final String amount, final String chargeableAmount) {
        return (Number) new SavingsAccountHelper(requestSpec, responseSpec).withdrawalFromSavingsAccount(savingsId.intValue(),
                withdrawalBody(date, amount, chargeableAmount), CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private String withdrawalBody(final String date, final String amount, final String chargeableAmount) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("locale", "en");
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("transactionDate", date);
        request.put("transactionAmount", new BigDecimal(amount));
        request.put("paymentTypeId", 1);
        if (chargeableAmount != null) {
            request.put("chargeableAmount", new BigDecimal(chargeableAmount));
        }
        return gson.toJson(request);
    }

    private String withdrawalUrl(final Long savingsId) {
        return "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions?command=withdrawal&" + Utils.TENANT_IDENTIFIER;
    }

    private void undo(final Long savingsId, final Number transactionId, final String date) {
        final String url = "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions/" + transactionId.longValue()
                + "?command=undo&" + Utils.TENANT_IDENTIFIER;
        final String body = gson
                .toJson(Map.of("transactionDate", date, "transactionAmount", "0", "dateFormat", DATETIME_PATTERN, "locale", "en"));
        Utils.performServerPost(requestSpec, responseSpec, url, body, CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private void reverse(final Long savingsId, final Number transactionId) {
        final String url = "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions/" + transactionId.longValue()
                + "?command=reverse&" + Utils.TENANT_IDENTIFIER;
        Utils.performServerPost(requestSpec, responseSpec, url, gson.toJson(Map.of("isBulk", true, "includeFees", true)),
                CommonConstants.RESPONSE_RESOURCE_ID);
    }

    // ---------- row readers ----------

    private BigDecimal chargeableAmountOf(final Long savingsId, final int transactionType) {
        return (BigDecimal) soleRow(savingsId, transactionType).get("chargeable_amount");
    }

    private BigDecimal runningBalanceOf(final Long savingsId, final int transactionType) {
        return (BigDecimal) soleRow(savingsId, transactionType).get("running_balance_derived");
    }

    private Map<String, Object> soleRow(final Long savingsId, final int transactionType) {
        final List<Map<String, Object>> rows = transactionRows(savingsId, transactionType).stream().filter(row -> !bool(row, "is_reversal"))
                .toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<BigDecimal> feeAmounts(final Long savingsId) {
        return transactionRows(savingsId, WITHDRAWAL_FEE).stream().filter(row -> !bool(row, "is_reversal"))
                .map(row -> (BigDecimal) row.get("amount")).toList();
    }

    private List<Map<String, Object>> transactionRows(final Long savingsId, final int... types) {
        final StringBuilder sql = new StringBuilder("""
                SELECT id, transaction_type_enum, amount, is_reversed, is_reversal, ref_no, chargeable_amount,
                       running_balance_derived
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
        return TenantJdbcSupport.tenantJdbc().queryForList(sql.toString(), savingsId);
    }

    private BigDecimal accountBalance(final Long savingsId) {
        return TenantJdbcSupport.tenantJdbc().queryForObject("SELECT account_balance_derived FROM m_savings_account WHERE id = ?",
                BigDecimal.class, savingsId);
    }

    private boolean bool(final Map<String, Object> row, final String column) {
        return Boolean.TRUE.equals(row.get(column));
    }
}
