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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.client.models.ChargeRequest;
import org.apache.fineract.client.models.GetFinancialActivityAccountsResponse;
import org.apache.fineract.client.models.PostChargesResponse;
import org.apache.fineract.client.models.PostFinancialActivityAccountsRequest;
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
    void postsPrincipalAndSuppliedReferencesToConfiguredJournalAccounts() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final String configuredSwitchId = uniqueSwitchId();
            final SwitchAccounting switchAccounting = configureSwitch(configuredSwitchId);
            final Account vatPayable = configureVatPayableMapping();
            final SavingsAccounting savingsAccounting = createSavingsAccounting();
            final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting);
            deposit(savingsId, date, new BigDecimal("1000.00"));

            withdraw(savingsId, date, "100.00", "  " + configuredSwitchId.toLowerCase() + "  ",
                    List.of(vat("7.00", "  First VAT journal note  "), commission("3.00", "Commission journal note", "1.00", "2.00"),
                            vat("2.00", "Last VAT journal note")));

            final List<Map<String, Object>> transactions = bundledTransactions(savingsId);
            assertThat(transactions).hasSize(4);
            assertTransaction(transactions.get(0), WITHDRAWAL, "100.00", "900.00");
            assertTransaction(transactions.get(1), VAT, "7.00", "893.00");
            assertTransaction(transactions.get(2), COMMISSION, "3.00", "890.00");
            assertTransaction(transactions.get(3), VAT, "2.00", "888.00");
            assertSharedReferenceAndSwitch(transactions, configuredSwitchId);
            assertThat(referenceNotes(savingsId))
                    .isEqualTo(List.of("  First VAT journal note  ", "Commission journal note", "Last VAT journal note"));
            assertThat(accountBalance(savingsId)).isEqualByComparingTo("888.00");

            assertJournalEntries(transactions.get(0), journalPosting("DEBIT", savingsAccounting.savingsControl(), "100.00"),
                    journalPosting("CREDIT", switchAccounting.payable(), "100.00"));
            assertJournalEntries(transactions.get(1), journalPosting("DEBIT", savingsAccounting.savingsControl(), "7.00"),
                    journalPosting("CREDIT", vatPayable, "7.00"));
            assertJournalEntries(transactions.get(2), journalPosting("DEBIT", savingsAccounting.savingsControl(), "3.00"),
                    journalPosting("CREDIT", switchAccounting.switchFee(), "1.00"),
                    journalPosting("CREDIT", switchAccounting.commissionIncome(), "2.00"));
            assertJournalEntries(transactions.get(3), journalPosting("DEBIT", savingsAccounting.savingsControl(), "2.00"),
                    journalPosting("CREDIT", vatPayable, "2.00"));
        });
    }

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

    private SwitchAccounting configureSwitch(final String switchId) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account payable = accountHelper.createLiabilityAccount("NIP Principal Payable");
        final Account switchFee = accountHelper.createExpenseAccount("NIP Switch Fee");
        final Account commissionIncome = accountHelper.createIncomeAccount("NIP Commission Income");
        final Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("switchPayableGlAccountId", payable.getAccountID());
        configuration.put("switchFeeGlAccountId", switchFee.getAccountID());
        configuration.put("commissionIncomeGlAccountId", commissionIncome.getAccountID());
        configuration.put("active", true);
        Utils.performServerPut(requestSpec, responseSpec,
                "/fineract-provider/api/v1/nip-switch-accounting-configurations/" + switchId + "?" + Utils.TENANT_IDENTIFIER,
                gson.toJson(configuration));
        return new SwitchAccounting(payable, switchFee, commissionIncome);
    }

    private Account configureVatPayableMapping() {
        final FinancialActivityAccountHelper helper = new FinancialActivityAccountHelper(requestSpec);
        final long vatPayableActivityId = FinancialActivity.VAT_PAYABLE.getValue();
        for (final GetFinancialActivityAccountsResponse mapping : helper.getAllFinancialActivityAccounts()) {
            if (mapping.getFinancialActivityData().getId() == vatPayableActivityId) {
                return new Account(mapping.getGlAccountData().getId().intValue(), Account.AccountType.LIABILITY);
            }
        }
        final Account vatPayable = new AccountHelper(requestSpec, responseSpec).createLiabilityAccount("NIP VAT Payable");
        helper.createFinancialActivityAccount(new PostFinancialActivityAccountsRequest().financialActivityId(vatPayableActivityId)
                .glAccountId(vatPayable.getAccountID().longValue()));
        return vatPayable;
    }

    private SavingsAccounting createSavingsAccounting() {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account savingsReference = accountHelper.createAssetAccount("NIP Savings Reference");
        final Account savingsControl = accountHelper.createLiabilityAccount("NIP Savings Control");
        final Account feeIncome = accountHelper.createIncomeAccount("NIP Product Fee Income");
        final Account interestExpense = accountHelper.createExpenseAccount("NIP Interest Expense");
        return new SavingsAccounting(savingsReference, savingsControl, feeIncome, interestExpense);
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

    @SuppressWarnings({ "rawtypes", "removal" })
    private void assertJournalEntries(final Map<String, Object> transaction, final JournalPosting... expectedPostings) {
        final Number transactionId = (Number) value(transaction, "id");
        final ArrayList<HashMap> entries = journalEntryHelper.getJournalEntriesByTransactionId("S" + transactionId.longValue());
        final List<JournalPosting> actualPostings = new ArrayList<>();
        for (final HashMap entry : entries) {
            final Map entryType = (Map) entry.get("entryType");
            actualPostings.add(new JournalPosting(entryType.get("value").toString(), ((Number) entry.get("glAccountId")).intValue(),
                    decimal(entry.get("amount"))));
        }
        final List<JournalPosting> expected = List.of(expectedPostings);
        assertThat(actualPostings).hasSameSizeAs(expected);
        assertThat(actualPostings.containsAll(expected) && expected.containsAll(actualPostings)).isTrue();
    }

    private JournalPosting journalPosting(final String entryType, final Account account, final String amount) {
        return new JournalPosting(entryType, account.getAccountID(), decimal(amount));
    }

    private BigDecimal decimal(final Object value) {
        return new BigDecimal(value.toString()).stripTrailingZeros();
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

    private void assertSharedReferenceAndSwitch(final List<Map<String, Object>> transactions, final String switchId) {
        final String rootReference = value(transactions.get(0), "ref_no").toString();
        for (final Map<String, Object> transaction : transactions) {
            assertThat(value(transaction, "ref_no")).isEqualTo(rootReference);
            assertThat(value(transaction, "switch_id")).isEqualTo(switchId);
        }
    }

    private String uniqueSwitchId() {
        return ("NIP_" + System.nanoTime()).toUpperCase();
    }

    private record SwitchAccounting(Account payable, Account switchFee, Account commissionIncome) {
    }

    private record SavingsAccounting(Account savingsReference, Account savingsControl, Account feeIncome, Account interestExpense) {

        Account[] productAccounts() {
            return new Account[] { savingsReference, savingsControl, feeIncome, interestExpense };
        }
    }

    private record JournalPosting(String entryType, Integer glAccountId, BigDecimal amount) {
    }
}
