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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({ "rawtypes", "removal" })
class NipInboundDepositIntegrationTest extends BaseSavingsIntegrationTest {

    private static final int DEPOSIT = 1;
    private static final int WITHDRAWAL = 2;
    private static final int EMT_LEVY = 24;

    private final Gson gson = new Gson();

    @Nested
    class InboundPrincipal {

        @Test
        void inboundOnlySwitchNormalizesTransactionContextAndDebitsReceivable() {
            final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
            runAt(date, () -> {
                final SwitchAccounting switchAccounting = configureInboundOnly(uniqueSwitchId());
                final SavingsAccounting savingsAccounting = createSavingsAccounting();
                final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting, null);

                deposit(savingsId, date, "100.00", "  " + switchAccounting.switchId().toLowerCase() + "  ", List.of());

                final List<Map<String, Object>> deposits = transactions(savingsId, DEPOSIT);
                final Map<String, Object> principal = deposits.get(deposits.size() - 1);
                assertThat(value(principal, "switch_id")).isEqualTo(switchAccounting.switchId());
                assertJournalEntries(principal, journalPosting("DEBIT", switchAccounting.receivable(), "100.00"),
                        journalPosting("CREDIT", savingsAccounting.savingsControl(), "100.00"));
            });
        }

        @Test
        void bidirectionalSwitchUsesReceivableForInboundAndPayableForOutbound() {
            final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
            runAt(date, () -> {
                final SwitchAccounting switchAccounting = configureBidirectional(uniqueSwitchId());
                final SavingsAccounting savingsAccounting = createSavingsAccounting();
                final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting, null);

                deposit(savingsId, date, "100.00", switchAccounting.switchId(), List.of());
                withdraw(savingsId, date, "25.00", switchAccounting.switchId());

                final Map<String, Object> inbound = transactions(savingsId, DEPOSIT).get(0);
                final Map<String, Object> outbound = transactions(savingsId, WITHDRAWAL).get(0);
                assertJournalEntries(inbound, journalPosting("DEBIT", switchAccounting.receivable(), "100.00"),
                        journalPosting("CREDIT", savingsAccounting.savingsControl(), "100.00"));
                assertJournalEntries(outbound, journalPosting("DEBIT", savingsAccounting.savingsControl(), "25.00"),
                        journalPosting("CREDIT", switchAccounting.payable(), "25.00"));
            });
        }
    }

    @Nested
    class InboundReferencesAndOverdraft {

        @Test
        void callerSuppliedEmtRetainsInboundContextAndUsesExistingEmtLiability() {
            final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
            runAt(date, () -> {
                final SwitchAccounting switchAccounting = configureInboundOnly(uniqueSwitchId());
                final Account emtLiability = configureEmtLevyMapping();
                final SavingsAccounting savingsAccounting = createSavingsAccounting();
                final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting, null);

                deposit(savingsId, date, "100.00", switchAccounting.switchId(), List.of(emtLevy("5.00")));

                final Map<String, Object> principal = transactions(savingsId, DEPOSIT).get(0);
                final Map<String, Object> levy = transactions(savingsId, EMT_LEVY).get(0);
                assertThat(value(principal, "switch_id")).isEqualTo(switchAccounting.switchId());
                assertThat(value(levy, "switch_id")).isEqualTo(switchAccounting.switchId());
                assertThat(value(levy, "ref_no")).isEqualTo(value(principal, "ref_no"));
                assertJournalEntries(principal, journalPosting("DEBIT", switchAccounting.receivable(), "100.00"),
                        journalPosting("CREDIT", savingsAccounting.savingsControl(), "100.00"));
                assertJournalEntries(levy, journalPosting("DEBIT", savingsAccounting.savingsControl(), "5.00"),
                        journalPosting("CREDIT", emtLiability, "5.00"));
            });
        }

        @Test
        void inboundPrincipalSplitsPartialOverdraftClearanceAcrossCustomerControls() {
            final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
            runAt(date, () -> {
                final SwitchAccounting switchAccounting = configureBidirectional(uniqueSwitchId());
                final SavingsAccounting savingsAccounting = createSavingsAccounting();
                final Account overdraftControl = new AccountHelper(requestSpec, responseSpec).createAssetAccount("NIP Overdraft Control");
                final Long savingsId = createActiveCashBasedSavingsAccount(date, savingsAccounting, overdraftControl);
                deposit(savingsId, date, "100.00", null, List.of());
                withdraw(savingsId, date, "130.00", switchAccounting.switchId());
                assertThat(accountBalance(savingsId)).isEqualByComparingTo("-30.00");

                deposit(savingsId, date, "50.00", switchAccounting.switchId(), List.of());

                final List<Map<String, Object>> deposits = transactions(savingsId, DEPOSIT);
                final Map<String, Object> principal = deposits.get(deposits.size() - 1);
                assertJournalEntries(principal, journalPosting("DEBIT", switchAccounting.receivable(), "50.00"),
                        journalPosting("CREDIT", overdraftControl, "30.00"),
                        journalPosting("CREDIT", savingsAccounting.savingsControl(), "20.00"));
            });
        }
    }

    @Test
    void rejectsInboundDepositForOutboundOnlySwitchBeforeCreatingATransaction() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final SwitchAccounting switchAccounting = configureOutboundOnly(uniqueSwitchId());
            final Long savingsId = createActiveCashBasedSavingsAccount(date, createSavingsAccounting(), null);
            final ResponseSpecification forbidden = new ResponseSpecBuilder().expectStatusCode(403).build();

            final List<Map<String, Object>> errors = (List<Map<String, Object>>) deposit(savingsId, date, "100.00",
                    switchAccounting.switchId(), List.of(), forbidden, CommonConstants.RESPONSE_ERROR);

            assertThat(errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE))
                    .isEqualTo("error.msg.nip.switch.accounting.configuration.direction.not.available");
            assertThat(transactions(savingsId, DEPOSIT)).isEmpty();
        });
    }

    private SwitchAccounting configureInboundOnly(final String switchId) {
        final Account receivable = new AccountHelper(requestSpec, responseSpec).createAssetAccount("NIP Switch Receivable");
        configureSwitch(switchId, "INBOUND", null, null, null, receivable);
        return new SwitchAccounting(switchId, null, receivable);
    }

    private SwitchAccounting configureOutboundOnly(final String switchId) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account payable = accountHelper.createLiabilityAccount("NIP Principal Payable");
        configureSwitch(switchId, "OUTBOUND", payable, accountHelper.createExpenseAccount("NIP Switch Fee"),
                accountHelper.createIncomeAccount("NIP Commission Income"), null);
        return new SwitchAccounting(switchId, payable, null);
    }

    private SwitchAccounting configureBidirectional(final String switchId) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account payable = accountHelper.createLiabilityAccount("NIP Principal Payable");
        final Account receivable = accountHelper.createAssetAccount("NIP Switch Receivable");
        configureSwitch(switchId, "BOTH", payable, accountHelper.createExpenseAccount("NIP Switch Fee"),
                accountHelper.createIncomeAccount("NIP Commission Income"), receivable);
        return new SwitchAccounting(switchId, payable, receivable);
    }

    private void configureSwitch(final String switchId, final String direction, final Account payable, final Account switchFee,
            final Account commissionIncome, final Account receivable) {
        final Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("direction", direction);
        configuration.put("switchPayableGlAccountId", accountId(payable));
        configuration.put("switchFeeGlAccountId", accountId(switchFee));
        configuration.put("commissionIncomeGlAccountId", accountId(commissionIncome));
        configuration.put("switchReceivableGlAccountId", accountId(receivable));
        configuration.put("active", true);
        Utils.performServerPut(requestSpec, responseSpec,
                "/fineract-provider/api/v1/nip-switch-accounting-configurations/" + switchId + "?" + Utils.TENANT_IDENTIFIER,
                gson.toJson(configuration));
    }

    private Integer accountId(final Account account) {
        return account == null ? null : account.getAccountID();
    }

    private Account configureEmtLevyMapping() {
        final FinancialActivityAccountHelper helper = new FinancialActivityAccountHelper(requestSpec);
        final long emtLevyActivityId = FinancialActivity.EMT_LEVY.getValue();
        for (final GetFinancialActivityAccountsResponse mapping : helper.getAllFinancialActivityAccounts()) {
            if (mapping.getFinancialActivityData().getId() == emtLevyActivityId) {
                return new Account(mapping.getGlAccountData().getId().intValue(), Account.AccountType.LIABILITY);
            }
        }
        final Account emtLiability = new AccountHelper(requestSpec, responseSpec).createLiabilityAccount("NIP EMT Levy Liability");
        helper.createFinancialActivityAccount(new PostFinancialActivityAccountsRequest().financialActivityId(emtLevyActivityId)
                .glAccountId(emtLiability.getAccountID().longValue()));
        return emtLiability;
    }

    private SavingsAccounting createSavingsAccounting() {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        return new SavingsAccounting(accountHelper.createAssetAccount("NIP Savings Reference"),
                accountHelper.createLiabilityAccount("NIP Savings Control"), accountHelper.createIncomeAccount("NIP Product Fee Income"),
                accountHelper.createExpenseAccount("NIP Interest Expense"));
    }

    private Long createActiveCashBasedSavingsAccount(final String date, final SavingsAccounting accounting,
            final Account overdraftControl) {
        final Long clientId = clientHelper
                .createClient(ClientHelper.defaultClientCreationRequest().dateFormat("yyyy-MM-dd").activationDate("2011-03-04"))
                .getClientId();
        final SavingsProductHelper productHelper = new SavingsProductHelper().withCurrencyCode("USD")
                .withInterestCompoundingPeriodTypeAsDaily().withInterestPostingPeriodTypeAsDaily()
                .withInterestCalculationPeriodTypeAsDailyBalance().withAccountingRuleAsCashBased(accounting.productAccounts());
        if (overdraftControl != null) {
            productHelper.withOverDraft("100.00");
        }
        String productRequest = productHelper.build();
        if (overdraftControl != null) {
            final Map<String, Object> product = gson.fromJson(productRequest, Map.class);
            product.put("overdraftPortfolioControlId", overdraftControl.getAccountID());
            productRequest = gson.toJson(product);
        }
        final Long productId = SavingsProductHelper.createSavingsProduct(productRequest, requestSpec, responseSpec).longValue();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
    }

    private void deposit(final Long savingsId, final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references) {
        deposit(savingsId, date, amount, switchId, references, responseSpec, CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private Object deposit(final Long savingsId, final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references, final ResponseSpecification expectedResponse, final String responseAttribute) {
        return new SavingsAccountHelper(requestSpec, expectedResponse).depositToSavingsAccount(savingsId.intValue(),
                gson.toJson(transactionRequest(date, amount, switchId, references)), responseAttribute);
    }

    private void withdraw(final Long savingsId, final String date, final String amount, final String switchId) {
        new SavingsAccountHelper(requestSpec, responseSpec).withdrawalFromSavingsAccount(savingsId.intValue(),
                gson.toJson(transactionRequest(date, amount, switchId, List.of())), CommonConstants.RESPONSE_RESOURCE_ID);
    }

    private Map<String, Object> transactionRequest(final String date, final String amount, final String switchId,
            final List<Map<String, Object>> references) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("locale", "en");
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("transactionDate", date);
        request.put("transactionAmount", new BigDecimal(amount));
        request.put("paymentTypeId", 1);
        request.put("switchId", switchId);
        request.put("referenceTransactions", references);
        return request;
    }

    private Map<String, Object> emtLevy(final String amount) {
        return Map.of("type", "EMT_LEVY", "amount", new BigDecimal(amount));
    }

    private List<Map<String, Object>> transactions(final Long savingsId, final int type) {
        return tenantJdbc().queryForList("""
                SELECT id, transaction_type_enum, amount, ref_no, switch_id
                  FROM m_savings_account_transaction
                 WHERE savings_account_id = ? AND transaction_type_enum = ?
                 ORDER BY id
                """, savingsId, type);
    }

    private BigDecimal accountBalance(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT account_balance_derived FROM m_savings_account WHERE id = ?", BigDecimal.class,
                savingsId);
    }

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
        assertThat(actualPostings.size()).as("actual postings: %s; expected postings: %s", actualPostings, expected)
                .isEqualTo(expected.size());
        assertThat(actualPostings.containsAll(expected) && expected.containsAll(actualPostings))
                .as("actual postings: %s; expected postings: %s", actualPostings, expected).isTrue();
    }

    private JournalPosting journalPosting(final String entryType, final Account account, final String amount) {
        return new JournalPosting(entryType, account.getAccountID(), decimal(amount));
    }

    private BigDecimal decimal(final Object value) {
        return new BigDecimal(value.toString()).stripTrailingZeros();
    }

    private Object value(final Map<String, Object> row, final String column) {
        final Object direct = row.get(column);
        return direct != null ? direct : row.get(column.toUpperCase());
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }

    private String uniqueSwitchId() {
        return ("NIP_INBOUND_" + System.nanoTime()).toUpperCase();
    }

    private record SwitchAccounting(String switchId, Account payable, Account receivable) {
    }

    private record SavingsAccounting(Account savingsReference, Account savingsControl, Account feeIncome, Account interestExpense) {

        Account[] productAccounts() {
            return new Account[] { savingsReference, savingsControl, feeIncome, interestExpense };
        }
    }

    private record JournalPosting(String entryType, Integer glAccountId, BigDecimal amount) {
    }
}
