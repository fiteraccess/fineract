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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.apache.fineract.integrationtests.support.TenantJdbcSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creating a transfer returns two ids: {@code resourceId} is the transfer details id and {@code subResourceId} is the
 * transfer transaction id. Only the second one identifies the transfer to {@code command=undo} and
 * {@code command=reverse}. The two normally coincide, which hides mistakes, so this test first pushes the details
 * sequence ahead (what creating a standing instruction does) to make them differ.
 */
class AccountTransferCreateThenUndoIntegrationTest extends BaseSavingsIntegrationTest {

    private final Gson gson = new Gson();

    private record Created(Long detailsId, Long transactionId) {
    }

    @Test
    void undoAndReverseActOnTheTransferNamedBySubResourceIdWhenTheIdsDiffer() {
        final String date = dateTimeFormatter.format(Utils.getLocalDateOfTenant());
        runAt(date, () -> {
            final Long source = createActivePlainSavingsAccount(date);
            final Long destination = createActivePlainSavingsAccount(date);
            deposit(source, date, new BigDecimal("100.00"));
            deposit(destination, date, new BigDecimal("100.00"));
            tenantJdbc().queryForObject("SELECT nextval(pg_get_serial_sequence('m_account_transfer_details', 'id'))", Long.class);

            final Created first = createTransfer(date, "40.00", source, destination);
            final Created second = createTransfer(date, "10.00", source, destination);

            assertThat(first.transactionId()).as("create returns the transfer transaction id").isNotNull();
            assertThat(first.transactionId()).as("ids were forced apart").isNotEqualTo(first.detailsId());
            assertThat(first.transactionId()).isEqualTo(transactionIdOfDetails(first.detailsId()));
            assertThat(second.transactionId()).isEqualTo(transactionIdOfDetails(second.detailsId()));
            assertThat(accountBalance(source)).isEqualByComparingTo("50.00");
            assertThat(accountBalance(destination)).isEqualByComparingTo("150.00");

            Utils.performServerPost(requestSpec, responseSpec, transferCommandUrl(first.transactionId(), "undo"), "");

            assertThat(transferReversed(first.transactionId())).as("the selected transfer is undone").isTrue();
            assertThat(transferReversed(second.transactionId())).as("the other transfer is untouched").isFalse();
            assertThat(accountBalance(source)).isEqualByComparingTo("90.00");
            assertThat(accountBalance(destination)).isEqualByComparingTo("110.00");

            Utils.performServerPost(requestSpec, responseSpec, transferCommandUrl(second.transactionId(), "reverse"), "{}");

            assertThat(transferReversed(second.transactionId())).isTrue();
            assertThat(accountBalance(source)).isEqualByComparingTo("100.00");
            assertThat(accountBalance(destination)).isEqualByComparingTo("100.00");
        });
    }

    @SuppressWarnings("unchecked")
    private Created createTransfer(final String date, final String amount, final Long source, final Long destination) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("dateFormat", DATETIME_PATTERN);
        request.put("locale", "en");
        request.put("fromOfficeId", 1);
        request.put("fromClientId", clientIdOf(source));
        request.put("fromAccountType", 2);
        request.put("fromAccountId", source);
        request.put("toOfficeId", 1);
        request.put("toClientId", clientIdOf(destination));
        request.put("toAccountType", 2);
        request.put("toAccountId", destination);
        request.put("transferDate", date);
        request.put("transferAmount", new BigDecimal(amount));
        request.put("transferDescription", "create then undo itest");
        request.put("sourceReferenceTransactions", List.of());
        request.put("destinationReferenceTransactions", List.of());
        final String json = Utils.performServerPost(requestSpec, responseSpec,
                "/fineract-provider/api/v1/accounttransfers?" + Utils.TENANT_IDENTIFIER, gson.toJson(request));
        final Map<String, Object> response = gson.fromJson(json, Map.class);
        return new Created(asLong(response.get("resourceId")), asLong(response.get("subResourceId")));
    }

    private Long createActivePlainSavingsAccount(final String date) {
        final Long clientId = clientHelper
                .createClient(ClientHelper.defaultClientCreationRequest().dateFormat("yyyy-MM-dd").activationDate("2011-03-04"))
                .getClientId();
        final var product = dailyInterestPostingProduct().currencyCode("USD").name(Utils.uniqueRandomStringGenerator("XFERUNDO_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4));
        final Long productId = createProduct(product).getResourceId();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, date)).getSavingsId();
        approveSavingsAccount(savingsId, date);
        activateSavingsAccount(savingsId, date);
        return savingsId;
    }

    private String transferCommandUrl(final Long transferTransactionId, final String command) {
        return "/fineract-provider/api/v1/accounttransfers/" + transferTransactionId + "?command=" + command + "&"
                + Utils.TENANT_IDENTIFIER;
    }

    private static Long asLong(final Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Long clientIdOf(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT client_id FROM m_savings_account WHERE id = ?", Long.class, savingsId);
    }

    private Long transactionIdOfDetails(final Long detailsId) {
        return tenantJdbc().queryForObject("SELECT id FROM m_account_transfer_transaction WHERE account_transfer_details_id = ?",
                Long.class, detailsId);
    }

    private boolean transferReversed(final Long transferTransactionId) {
        return Boolean.TRUE.equals(tenantJdbc().queryForObject("SELECT is_reversed FROM m_account_transfer_transaction WHERE id = ?",
                Boolean.class, transferTransactionId));
    }

    private BigDecimal accountBalance(final Long savingsId) {
        return tenantJdbc().queryForObject("SELECT account_balance_derived FROM m_savings_account WHERE id = ?", BigDecimal.class,
                savingsId);
    }

    private JdbcTemplate tenantJdbc() {
        return TenantJdbcSupport.tenantJdbc();
    }
}
