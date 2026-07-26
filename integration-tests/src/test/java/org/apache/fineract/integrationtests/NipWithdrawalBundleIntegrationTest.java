/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership. The ASF licenses this file to You under
 * the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing permissions and limitations under the
 * License.
 */
package org.apache.fineract.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.client.models.ChargeRequest;
import org.apache.fineract.client.models.PostChargesResponse;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesRequest;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.FinancialActivityAccountHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.apache.fineract.integrationtests.support.TenantJdbcSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NipWithdrawalBundleIntegrationTest extends BaseSavingsIntegrationTest {

    private static final int WITHDRAWAL = 2;
    private static final int WITHDRAWAL_FEE = 4;
    private static final int COMMISSION = 25;
    private static final int VAT = 26;

    private final Gson gson = new Gson();

    @Test
    void preservesReferenceOrderProductFeeRunningBalancesSwitchAndNotes() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String configuredSwitchId = uniqueSwitchId();
            configureSwitch(configuredSwitchId);
            configureVatPayableMapping();
            final Long savingsId = createActiveSavingsAccount(date, false);
            deposit(savingsId, date, new BigDecimal("1000.00"));
            addFixedWithdrawalFee(savingsId, "10.00");

            withdraw(savingsId, date, "100.00", "  " + configuredSwitchId.toLowerCase() + "  ", List.of(vat("7.00", "  First VAT note  "),
                    commission("3.00", "Commission note in the middle", "1.00", "2.00"), vat("2.00", "Last VAT note")));

            final List<Map<String, Object>> transactions = bundledTransactions(savingsId);
            assertThat(transactions).hasSize(5);
            assertTransaction(transactions.get(0), WITHDRAWAL, "100.00", "890.00");
            assertTransaction(transactions.get(1), WITHDRAWAL_FEE, "10.00", "890.00");
            assertTransaction(transactions.get(2), VAT, "7.00", "883.00");
            assertTransaction(transactions.get(3), COMMISSION, "3.00", "880.00");
            assertTransaction(transactions.get(4), VAT, "2.00", "878.00");

            final String rootReference = value(transactions.get(0), "ref_no").toString();
            for (final Map<String, Object> transaction : transactions) {
                assertThat(value(transaction, "ref_no")).isEqualTo(rootReference);
            }
            assertThat(value(transactions.get(0), "switch_id")).isEqualTo(configuredSwitchId);
            assertThat(value(transactions.get(1), "switch_id")).isNull();
            assertThat(value(transactions.get(2), "switch_id")).isEqualTo(configuredSwitchId);
            assertThat(value(transactions.get(3), "switch_id")).isEqualTo(configuredSwitchId);
            assertThat(value(transactions.get(4), "switch_id")).isEqualTo(configuredSwitchId);

            assertThat(accountBalance(savingsId)).isEqualByComparingTo("878.00");
            assertThat(referenceNotes(savingsId))
                    .isEqualTo(List.of("  First VAT note  ", "Commission note in the middle", "Last VAT note"));
        });
    }

    @Test
    void feeFreeRequestCreatesOnlyPrincipalAndNoReferenceNotes() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String configuredSwitchId = uniqueSwitchId();
            configureSwitch(configuredSwitchId);
            final Long savingsId = createActiveSavingsAccount(date, false);
            deposit(savingsId, date, new BigDecimal("100.00"));

            withdraw(savingsId, date, "25.00", configuredSwitchId, List.of());

            final List<Map<String, Object>> transactions = bundledTransactions(savingsId);
            assertThat(transactions).hasSize(1);
            assertTransaction(transactions.get(0), WITHDRAWAL, "25.00", "75.00");
            assertThat(value(transactions.get(0), "switch_id")).isEqualTo(configuredSwitchId);
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("75.00");
            assertThat(referenceNotes(savingsId)).isEmpty();
        });
    }

    @Test
    void permitsCompletePrincipalAndReferencesWithinProductOverdraftLimit() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String configuredSwitchId = uniqueSwitchId();
            configureSwitch(configuredSwitchId);
            final Long savingsId = createActiveOverdraftSavingsAccount(date, "50.00");
            deposit(savingsId, date, new BigDecimal("100.00"));

            withdraw(savingsId, date, "120.00", configuredSwitchId,
                    List.of(commission("10.00", "First overdraft commission", "4.00", "6.00"),
                            commission("10.00", "Second overdraft commission", "5.00", "5.00")));

            final List<Map<String, Object>> transactions = bundledTransactions(savingsId);
            assertThat(transactions).hasSize(3);
            assertTransaction(transactions.get(0), WITHDRAWAL, "120.00", "-20.00");
            assertTransaction(transactions.get(1), COMMISSION, "10.00", "-30.00");
            assertTransaction(transactions.get(2), COMMISSION, "10.00", "-40.00");
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("-40.00");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollsBackPrincipalWhenReferencesExceedPermittedOverdraft() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String configuredSwitchId = uniqueSwitchId();
            configureSwitch(configuredSwitchId);
            final Long savingsId = createActiveOverdraftSavingsAccount(date, "20.00");
            deposit(savingsId, date, new BigDecimal("100.00"));
            final List<Long> transactionIdsBefore = transactionIds(savingsId);
            final BigDecimal balanceBefore = accountBalance(savingsId);
            final ResponseSpecification forbidden = new ResponseSpecBuilder().expectStatusCode(403).build();

            final List<Map<String, Object>> errors = (List<Map<String, Object>>) withdraw(savingsId, date, "110.00", configuredSwitchId,
                    List.of(commission("15.00", "Must roll back", "5.00", "10.00")), forbidden, CommonConstants.RESPONSE_ERROR);

            assertThat(errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE))
                    .isEqualTo("error.msg.savingsaccount.transaction.insufficient.account.balance");
            assertThat(accountBalance(savingsId)).isEqualByComparingTo(balanceBefore);
            assertThat(transactionIds(savingsId)).isEqualTo(transactionIdsBefore);
            assertThat(referenceNotes(savingsId)).isEmpty();
        });
    }

    private Long createActiveSavingsAccount(final String date, final boolean allowOverdraft) {
        final Long clientId = createClient();
        final PostSavingsProductsRequest product = dailyInterestPostingProduct().currencyCode("USD")
                .name(Utils.uniqueRandomStringGenerator("NIP_BUNDLE_", 6)).shortName(Utils.uniqueRandomStringGenerator("", 4))
                .allowOverdraft(allowOverdraft);
        final Long productId = createProduct(product).getResourceId();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
    }

    @SuppressWarnings("removal")
    private Long createActiveOverdraftSavingsAccount(final String date, final String overdraftLimit) {
        final Long clientId = createClient();
        final String productJson = new SavingsProductHelper().withAccountingRuleAsNone().withOverDraft(overdraftLimit).build();
        final Long productId = SavingsProductHelper.createSavingsProduct(productJson, requestSpec, responseSpec).longValue();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
    }

    private Long createClient() {
        return clientHelper.createClient(ClientHelper.defaultClientCreationRequest().dateFormat("yyyy-MM-dd").activationDate("2011-03-04"))
                .getClientId();
    }

    private void addFixedWithdrawalFee(final Long savingsId, final String amount) {
        final PostChargesResponse charge = new ChargesHelper().createCharges(new ChargeRequest().active(true)
                .name(Utils.uniqueRandomStringGenerator("NIP_WITHDRAWAL_FEE_", 6)).currencyCode("USD")
                .amount(new BigDecimal(amount).doubleValue()).chargeAppliesTo(2).chargeTimeType(5).chargeCalculationType(1).locale("en"));
        ok(fineractClient().savingsAccountCharges.addSavingsAccountCharge(savingsId, new PostSavingsAccountsSavingsAccountIdChargesRequest()
                .chargeId(charge.getResourceId()).amount(new BigDecimal(amount).floatValue()).locale("en")));
    }

    private void configureSwitch(final String switchId) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account payable = accountHelper.createLiabilityAccount();
        final Account switchFee = accountHelper.createExpenseAccount();
        final Account commissionIncome = accountHelper.createIncomeAccount();
        final Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("switchPayableGlAccountId", payable.getAccountID());
        configuration.put("switchFeeGlAccountId", switchFee.getAccountID());
        configuration.put("commissionIncomeGlAccountId", commissionIncome.getAccountID());
        configuration.put("active", true);
        Utils.performServerPut(requestSpec, responseSpec,
                "/fineract-provider/api/v1/nip-switch-accounting-configurations/" + switchId + "?" + Utils.TENANT_IDENTIFIER,
                gson.toJson(configuration));
    }

    @SuppressWarnings({ "rawtypes", "removal" })
    private void configureVatPayableMapping() {
        final Account vatPayable = new AccountHelper(requestSpec, responseSpec).createLiabilityAccount();
        final FinancialActivityAccountHelper helper = new FinancialActivityAccountHelper(requestSpec);
        final int vatPayableActivityId = FinancialActivity.VAT_PAYABLE.getValue();
        for (final HashMap mapping : helper.getAllFinancialActivityAccounts(responseSpec)) {
            final Map financialActivity = (Map) mapping.get("financialActivityData");
            if (((Number) financialActivity.get("id")).intValue() == vatPayableActivityId) {
                helper.updateFinancialActivityAccount(((Number) mapping.get("id")).intValue(), vatPayableActivityId,
                        vatPayable.getAccountID(), responseSpec, CommonConstants.RESPONSE_CHANGES);
                return;
            }
        }
        helper.createFinancialActivityAccount(vatPayableActivityId, vatPayable.getAccountID(), responseSpec,
                CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private void withdraw(final Long savingsId, final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references) {
        withdraw(savingsId, date, amount, switchId, references, responseSpec);
    }

    @SuppressWarnings("removal")
    private Object withdraw(final Long savingsId, final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references, final ResponseSpecification expectedResponse) {
        return withdraw(savingsId, date, amount, switchId, references, expectedResponse, CommonConstants.RESPONSE_RESOURCE_ID);
    }

    @SuppressWarnings("removal")
    private Object withdraw(final Long savingsId, final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references, final ResponseSpecification expectedResponse, final String responseAttribute) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("locale", "en");
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("transactionDate", date);
        request.put("transactionAmount", new BigDecimal(amount));
        request.put("paymentTypeId", 1);
        request.put("switchId", switchId);
        request.put("referenceTransactions", references);
        return new SavingsAccountHelper(requestSpec, expectedResponse).withdrawalFromSavingsAccount(savingsId.intValue(),
                gson.toJson(request), responseAttribute);
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

    private List<Map<String, Object>> bundledTransactions(final Long savingsId) {
        return tenantJdbc().queryForList("""
                SELECT id, transaction_type_enum, amount, running_balance_derived, ref_no, switch_id
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ?
                   AND transaction_type_enum IN (?, ?, ?, ?)
                 ORDER BY id
                """, savingsId, WITHDRAWAL, WITHDRAWAL_FEE, COMMISSION, VAT);
    }

    private List<String> referenceNotes(final Long savingsId) {
        return tenantJdbc().queryForList("""
                SELECT n.note
                  FROM m_note n
                  JOIN m_savings_account_transaction t ON t.id = n.savings_account_transaction_id
                 WHERE n.savings_account_id = ?
                   AND t.transaction_type_enum IN (?, ?)
                 ORDER BY t.id
                """, String.class, savingsId, COMMISSION, VAT);
    }

    private BigDecimal accountBalance(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT account_balance_derived FROM m_savings_account WHERE id = ?", BigDecimal.class,
                savingsId);
    }

    private List<Long> transactionIds(final Long savingsId) {
        return tenantJdbc().queryForList("SELECT id FROM m_savings_account_transaction WHERE savings_account_id = ? ORDER BY id",
                Long.class, savingsId);
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }

    private void assertTransaction(final Map<String, Object> transaction, final int type, final String amount,
            final String runningBalance) {
        assertThat(((Number) value(transaction, "transaction_type_enum")).intValue()).isEqualTo(type);
        assertThat((BigDecimal) value(transaction, "amount")).isEqualByComparingTo(amount);
        assertThat((BigDecimal) value(transaction, "running_balance_derived")).isEqualByComparingTo(runningBalance);
    }

    private Object value(final Map<String, Object> row, final String column) {
        final Object direct = row.get(column);
        return direct != null ? direct : row.get(column.toUpperCase());
    }

    private String uniqueSwitchId() {
        return ("NIP_" + System.nanoTime()).toUpperCase();
    }
}
