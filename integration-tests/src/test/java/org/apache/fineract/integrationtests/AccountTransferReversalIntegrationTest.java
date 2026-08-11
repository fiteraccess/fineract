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
 * End-to-end coverage of AB-412 F3 — account-transfer reversal via {@code POST
 * /v1/accounttransfers/{transferId}?command=reverse}: both legs reversed with mirrors, the transfer row flagged,
 * journal entries linked on both legs, the already-reversed guard, atomic rollback when the destination has spent the
 * funds, and the guards that route leg-level reverse/undo attempts to the transfer command.
 */
class AccountTransferReversalIntegrationTest extends BaseSavingsIntegrationTest {

    private static final int JE_CREDIT = 1;
    private static final int JE_DEBIT = 2;

    private final Gson gson = new Gson();

    @Test
    @SuppressWarnings("unchecked")
    void reverseReversesBothLegsFlagsTransferAndLinksJournals() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            configureLiabilityTransferMapping();
            final Long sourceSavingsId = createActiveCashBasedSavingsAccount(date);
            final Long destinationSavingsId = createActiveCashBasedSavingsAccount(date);
            deposit(sourceSavingsId, date, new BigDecimal("100.00"));
            deposit(destinationSavingsId, date, new BigDecimal("100.00"));
            final Transfer transfer = settleTransfer(date, "40.00", sourceSavingsId, destinationSavingsId);
            assertThat(accountBalance(sourceSavingsId)).isEqualByComparingTo("60.00");
            assertThat(accountBalance(destinationSavingsId)).isEqualByComparingTo("140.00");

            final Map<String, Object> changes = (Map<String, Object>) Utils.performServerPost(requestSpec, responseSpec,
                    transferReverseUrl(transfer.transferId()), "{}", "changes");

            assertThat(changes.get("withdrawalReversalId")).isNotNull();
            assertThat(changes.get("depositReversalId")).isNotNull();
            assertThat(tenantJdbc().queryForObject("SELECT is_reversed FROM m_account_transfer_transaction WHERE id = ?", Boolean.class,
                    transfer.transferId())).isTrue();
            assertThat(bool(transactionRow(transfer.withdrawalLegId()), "is_reversed")).isTrue();
            assertThat(bool(transactionRow(transfer.depositLegId()), "is_reversed")).isTrue();
            assertThat(accountBalance(sourceSavingsId)).isEqualByComparingTo("100.00");
            assertThat(accountBalance(destinationSavingsId)).isEqualByComparingTo("100.00");

            assertBookingEntriesLinked(transfer.withdrawalLegId(), 2);
            assertBookingEntriesLinked(transfer.depositLegId(), 2);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void secondReverseSurfacesTheAlreadyReversedGuard() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Transfer transfer = createSettledPlainTransfer(date, "40.00");
            Utils.performServerPost(requestSpec, responseSpec, transferReverseUrl(transfer.transferId()), "{}", "changes");

            final ResponseSpecification forbidden = new ResponseSpecBuilder().expectStatusCode(403).build();
            final List<Map<String, Object>> errors = (List<Map<String, Object>>) Utils.performServerPost(requestSpec, forbidden,
                    transferReverseUrl(transfer.transferId()), "{}", CommonConstants.RESPONSE_ERROR);

            assertThat(errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE))
                    .isEqualTo("error.msg.accounttransfer.already.reversed");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void reverseFailsAtomicallyWhenDestinationHasSpentTheFunds() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Transfer transfer = createSettledPlainTransfer(date, "40.00");
            // Destination spends the transferred money: 140 - 120 leaves 20 < 40, so the deposit-leg
            // reversal (a debit) must fail the balance check before the withdrawal leg is touched.
            withdrawCash(transfer.destinationSavingsId(), date, "120.00");

            final ResponseSpecification forbidden = new ResponseSpecBuilder().expectStatusCode(403).build();
            final List<Map<String, Object>> errors = (List<Map<String, Object>>) Utils.performServerPost(requestSpec, forbidden,
                    transferReverseUrl(transfer.transferId()), "{}", CommonConstants.RESPONSE_ERROR);

            assertThat((String) errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE)).contains("insufficient");
            assertThat(tenantJdbc().queryForObject("SELECT is_reversed FROM m_account_transfer_transaction WHERE id = ?", Boolean.class,
                    transfer.transferId())).as("transfer must not be flagged after a failed reversal").isFalse();
            assertThat(bool(transactionRow(transfer.withdrawalLegId()), "is_reversed")).as("withdrawal leg untouched").isFalse();
            assertThat(bool(transactionRow(transfer.depositLegId()), "is_reversed")).as("deposit leg untouched").isFalse();
            assertThat(accountBalance(transfer.sourceSavingsId())).isEqualByComparingTo("60.00");
            assertThat(accountBalance(transfer.destinationSavingsId())).isEqualByComparingTo("20.00");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void directLegReversalPointsAtTheTransferCommand() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Transfer transfer = createSettledPlainTransfer(date, "40.00");

            final ResponseSpecification forbidden = new ResponseSpecBuilder().expectStatusCode(403).build();
            final List<Map<String, Object>> errors = (List<Map<String, Object>>) Utils.performServerPost(requestSpec, forbidden,
                    savingsCommandUrl(transfer.sourceSavingsId(), transfer.withdrawalLegId(), "reverse"), "{}",
                    CommonConstants.RESPONSE_ERROR);

            assertThat(errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE))
                    .isEqualTo("error.msg.saving.account.transfer.transaction.reverse.via.transfer.command");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void undoOfTransferLegIsRejectedAsServiceUnavailable() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Transfer transfer = createSettledPlainTransfer(date, "40.00");

            final String body = gson
                    .toJson(Map.of("transactionDate", date, "transactionAmount", "0", "dateFormat", DATETIME_PATTERN, "locale", "en"));
            final ResponseSpecification unavailable = new ResponseSpecBuilder().expectStatusCode(503).build();
            final List<Map<String, Object>> errors = (List<Map<String, Object>>) Utils.performServerPost(requestSpec, unavailable,
                    savingsCommandUrl(transfer.sourceSavingsId(), transfer.withdrawalLegId(), "undo"), body,
                    CommonConstants.RESPONSE_ERROR);

            assertThat(errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE))
                    .isEqualTo("error.msg.saving.account.transfer.transaction.update.not.allowed");
        });
    }

    // ---------- fixtures ----------

    private record Transfer(Long transferId, Long sourceSavingsId, Long destinationSavingsId, Number withdrawalLegId, Number depositLegId) {
    }

    private Transfer createSettledPlainTransfer(final String date, final String amount) {
        final Long sourceSavingsId = createActivePlainSavingsAccount(date);
        final Long destinationSavingsId = createActivePlainSavingsAccount(date);
        deposit(sourceSavingsId, date, new BigDecimal("100.00"));
        deposit(destinationSavingsId, date, new BigDecimal("100.00"));
        return settleTransfer(date, amount, sourceSavingsId, destinationSavingsId);
    }

    private Transfer settleTransfer(final String date, final String amount, final Long sourceSavingsId, final Long destinationSavingsId) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("locale", "en");
        request.put("fromOfficeId", 1);
        request.put("fromClientId", clientIdOf(sourceSavingsId));
        request.put("fromAccountType", 2);
        request.put("fromAccountId", sourceSavingsId);
        request.put("toOfficeId", 1);
        request.put("toClientId", clientIdOf(destinationSavingsId));
        request.put("toAccountType", 2);
        request.put("toAccountId", destinationSavingsId);
        request.put("transferDate", date);
        request.put("transferAmount", new BigDecimal(amount));
        request.put("transferDescription", "transfer reversal itest");
        Utils.performServerPost(requestSpec, responseSpec, "/fineract-provider/api/v1/accounttransfers?" + Utils.TENANT_IDENTIFIER,
                gson.toJson(request), CommonConstants.RESPONSE_RESOURCE_ID);

        final Map<String, Object> transfer = tenantJdbc().queryForMap("""
                SELECT att.id AS transfer_id, att.from_savings_transaction_id AS withdrawal_id, att.to_savings_transaction_id AS deposit_id
                  FROM m_account_transfer_transaction att
                  JOIN m_savings_account_transaction sat ON sat.id = att.from_savings_transaction_id
                 WHERE sat.savings_account_id = ?
                """, sourceSavingsId);
        return new Transfer(((Number) transfer.get("transfer_id")).longValue(), sourceSavingsId, destinationSavingsId,
                (Number) transfer.get("withdrawal_id"), (Number) transfer.get("deposit_id"));
    }

    private Long createActivePlainSavingsAccount(final String date) {
        final Long clientId = createClient();
        final var product = dailyInterestPostingProduct().currencyCode("USD").name(Utils.uniqueRandomStringGenerator("XFERREV_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4));
        final Long productId = createProduct(product).getResourceId();
        return openActiveAccount(clientId, productId, date);
    }

    @SuppressWarnings("removal")
    private Long createActiveCashBasedSavingsAccount(final String date) {
        final AccountHelper accountHelper = new AccountHelper(requestSpec, responseSpec);
        final Account[] accounts = { accountHelper.createAssetAccount("Xfer Reversal Savings Reference"),
                accountHelper.createLiabilityAccount("Xfer Reversal Savings Control"),
                accountHelper.createIncomeAccount("Xfer Reversal Fee Income"),
                accountHelper.createExpenseAccount("Xfer Reversal Interest Expense") };
        final String productJson = new SavingsProductHelper().withCurrencyCode("USD").withInterestCompoundingPeriodTypeAsDaily()
                .withInterestPostingPeriodTypeAsDaily().withInterestCalculationPeriodTypeAsDailyBalance()
                .withAccountingRuleAsCashBased(accounts).build();
        final Long productId = SavingsProductHelper.createSavingsProduct(productJson, requestSpec, responseSpec).longValue();
        return openActiveAccount(createClient(), productId, date);
    }

    private Long openActiveAccount(final Long clientId, final Long productId, final String date) {
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
    }

    private Long createClient() {
        return clientHelper.createClient(ClientHelper.defaultClientCreationRequest().dateFormat("yyyy-MM-dd").activationDate("2011-03-04"))
                .getClientId();
    }

    private void configureLiabilityTransferMapping() {
        final long liabilityTransferActivityId = FinancialActivity.LIABILITY_TRANSFER.getValue();
        final FinancialActivityAccountHelper helper = new FinancialActivityAccountHelper(requestSpec);
        for (final GetFinancialActivityAccountsResponse mapping : helper.getAllFinancialActivityAccounts()) {
            if (mapping.getFinancialActivityData().getId() == liabilityTransferActivityId) {
                return;
            }
        }
        final Account account = new AccountHelper(requestSpec, responseSpec).createLiabilityAccount("Xfer Reversal Liability Transfer");
        helper.createFinancialActivityAccount(
                new PostFinancialActivityAccountsRequest().financialActivityId((long) FinancialActivity.LIABILITY_TRANSFER.getValue())
                        .glAccountId(account.getAccountID().longValue()));
    }

    private void withdrawCash(final Long savingsId, final String date, final String amount) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("locale", "en");
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("transactionDate", date);
        request.put("transactionAmount", new BigDecimal(amount));
        request.put("paymentTypeId", 1);
        new SavingsAccountHelper(requestSpec, responseSpec).withdrawalFromSavingsAccount(savingsId.intValue(), gson.toJson(request),
                CommonConstants.RESPONSE_RESOURCE_ID);
    }

    // ---------- urls & readers ----------

    private String transferReverseUrl(final Long transferId) {
        return "/fineract-provider/api/v1/accounttransfers/" + transferId + "?command=reverse&" + Utils.TENANT_IDENTIFIER;
    }

    private String savingsCommandUrl(final Long savingsId, final Number transactionId, final String command) {
        return "/fineract-provider/api/v1/savingsaccounts/" + savingsId + "/transactions/" + transactionId.longValue() + "?command="
                + command + "&" + Utils.TENANT_IDENTIFIER;
    }

    private Long clientIdOf(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT client_id FROM m_savings_account WHERE id = ?", Long.class, savingsId);
    }

    private Map<String, Object> transactionRow(final Number transactionId) {
        return tenantJdbc().queryForMap("SELECT is_reversed, is_reversal FROM m_savings_account_transaction WHERE id = ?",
                transactionId.longValue());
    }

    private BigDecimal accountBalance(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT account_balance_derived FROM m_savings_account WHERE id = ?", BigDecimal.class,
                savingsId);
    }

    /**
     * Same linkage shape the savings reversal suite asserts: booking legs flagged reversed with reversal_id pointing at
     * a live contra leg.
     */
    private void assertBookingEntriesLinked(final Number savingsTransactionId, final int legsPerSide) {
        final List<Map<String, Object>> entries = tenantJdbc().queryForList("""
                SELECT id, type_enum, account_id, amount, reversed, reversal_id
                  FROM acc_gl_journal_entry
                 WHERE savings_transaction_id = ?
                 ORDER BY id
                """, savingsTransactionId.longValue());
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

    private boolean bool(final Map<String, Object> row, final String column) {
        return Boolean.TRUE.equals(row.get(column));
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }
}
