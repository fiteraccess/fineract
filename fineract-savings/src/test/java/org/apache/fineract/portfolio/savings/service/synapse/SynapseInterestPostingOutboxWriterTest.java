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
package org.apache.fineract.portfolio.savings.service.synapse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.synapse.AccountCursorUpdate;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SynapseInterestPostingOutboxWriterTest {

    private final SynapseInstructionMapper mapper = new SynapseInstructionMapper();
    private final SynapseOutboxRepository outboxRepository = mock(SynapseOutboxRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SynapseInterestPostingOutboxWriter service = new SynapseInterestPostingOutboxWriter(mapper, outboxRepository,
            objectMapper);

    private static final LocalDate POSTING_DATE = LocalDate.of(2026, 3, 20);
    private static final LocalDate INTEREST_POSTED_TILL = LocalDate.of(2026, 3, 20);
    private static final LocalDate LAST_CALC_DATE = LocalDate.of(2026, 3, 20);

    @SuppressWarnings("unchecked")
    @Test
    void happyPathThreeAccountsAllWrittenToOutbox() {
        SavingsAccountData acct1 = buildAccountWithInterestTx(1L, 10L, "NGN", new BigDecimal("100.00"));
        SavingsAccountData acct2 = buildAccountWithInterestTx(2L, 10L, "NGN", new BigDecimal("200.00"));
        SavingsAccountData acct3 = buildAccountWithInterestTx(3L, 10L, "NGN", new BigDecimal("50.00"));

        SynapsePostResult result = service.postInterestBatch(List.of(acct1, acct2, acct3));

        assertThat(result.getAccepted()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(3);
        assertThat(result.getCursorUpdates()).extracting(AccountCursorUpdate::getAccountId).containsExactly(1L, 2L, 3L);

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(eq("INTEREST_POSTING"), anyString(), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(3);
    }

    @Test
    void zeroInterestAccountsSkipOutboxWriteAndReturnAllCursors() {
        SavingsAccountData acct1 = buildAccountNoTx(1L, 10L, "NGN");
        SavingsAccountData acct2 = buildAccountNoTx(2L, 10L, "NGN");

        SynapsePostResult result = service.postInterestBatch(List.of(acct1, acct2));

        verify(outboxRepository, never()).insertBatch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
        assertThat(result.getAccepted()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(2);
        assertThat(result.getCursorUpdates()).extracting(AccountCursorUpdate::getAccountId).containsExactly(1L, 2L);
        assertThat(result.getCursorUpdates()).extracting(AccountCursorUpdate::getInterestPostedTillDate).containsExactly(LAST_CALC_DATE,
                LAST_CALC_DATE);
    }

    @SuppressWarnings("unchecked")
    @Test
    void multipleInstructionsForAccountAllWrittenToOutbox() {
        SavingsAccountData acct = buildAccountWithTwoTx(1L, 10L, "NGN", new BigDecimal("100.00"), new BigDecimal("10.00"));

        SynapsePostResult result = service.postInterestBatch(List.of(acct));

        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(1);
        assertThat(result.getCursorUpdates().get(0).getAccountId()).isEqualTo(1L);
        assertThat(result.getCursorUpdates().get(0).getInterestPostedTillDate()).isEqualTo(INTEREST_POSTED_TILL);
        assertThat(result.getCursorUpdates().get(0).getLastInterestCalculationDate()).isEqualTo(LAST_CALC_DATE);

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(eq("INTEREST_POSTING"), anyString(), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void outboxEntriesContainCorrectAccountAndOfficeIds() {
        SavingsAccountData acct1 = buildAccountWithInterestTx(1L, 10L, "NGN", new BigDecimal("100.00"));
        SavingsAccountData acct2 = buildAccountWithInterestTx(2L, 20L, "NGN", new BigDecimal("200.00"));

        service.postInterestBatch(List.of(acct1, acct2));

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(eq("INTEREST_POSTING"), anyString(), entriesCaptor.capture());
        List<OutboxEntry> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(OutboxEntry::getAccountId).containsExactly(1L, 2L);
        assertThat(entries).extracting(OutboxEntry::getOfficeId).containsExactly(10L, 20L);
        assertThat(entries).allSatisfy(e -> assertThat(e.getPayload()).isNotBlank());
    }

    @Test
    void cursorFallsBackToLastCalcDateWhenInterestPostedTillDateIsNull() {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(new CurrencyData("NGN"), null, null, null, null, null, null, null,
                null, null, null, null, null, LocalDate.of(2026, 3, 15), null, null);
        SavingsAccountData acct = buildAccountWithSummary(1L, 10L, "NGN", summary);

        SynapsePostResult result = service.postInterestBatch(List.of(acct));

        verify(outboxRepository, never()).insertBatch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
        assertThat(result.getCursorUpdates()).hasSize(1);
        assertThat(result.getCursorUpdates().get(0).getInterestPostedTillDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(result.getCursorUpdates().get(0).getLastInterestCalculationDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void zeroAmountTransactionsSkipOutboxWrite() {
        SavingsAccountData acct = buildAccountWithInterestTx(1L, 10L, "NGN", BigDecimal.ZERO);

        SynapsePostResult result = service.postInterestBatch(List.of(acct));

        verify(outboxRepository, never()).insertBatch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getCursorUpdates()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void reversedTransactionWrittenAsReverseOperation() {
        SavingsAccountData acct = buildAccountNoTx(1L, 10L, "NGN");
        SavingsAccountTransactionEnumData txType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(99L, txType, null, 1L, "SA-1", POSTING_DATE, null,
                new BigDecimal("75.00"), null, null, false, null, false, null, null, POSTING_DATE);
        tx.reverse();
        acct.setSavingsAccountTransactionData(tx);

        SynapsePostResult result = service.postInterestBatch(List.of(acct));

        assertThat(result.getAccepted()).isEqualTo(1);
        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(eq("INTEREST_POSTING"), anyString(), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(1);
        assertThat(entriesCaptor.getValue().get(0).getPayload()).contains("REVERSE");
    }

    @SuppressWarnings("unchecked")
    @Test
    void postInterestForAccountDelegatesToBatchWithSingleAccount() {
        SavingsAccountData acct = buildAccountWithInterestTx(1L, 10L, "NGN", new BigDecimal("250.00"));

        SynapsePostResult result = service.postInterestForAccount(acct);

        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(1);
        assertThat(result.getCursorUpdates().get(0).getAccountId()).isEqualTo(1L);
        assertThat(result.getCursorUpdates().get(0).getInterestPostedTillDate()).isEqualTo(INTEREST_POSTED_TILL);
        assertThat(result.getCursorUpdates().get(0).getLastInterestCalculationDate()).isEqualTo(LAST_CALC_DATE);

        ArgumentCaptor<List<OutboxEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertBatch(eq("INTEREST_POSTING"), anyString(), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(1);
    }

    // --- account builders ---

    private static SavingsAccountData buildAccountWithInterestTx(Long id, Long officeId, String currencyCode, BigDecimal amount) {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(new CurrencyData(currencyCode), null, null, null, null, null,
                null, null, null, null, null, null, null, LAST_CALC_DATE, null, INTEREST_POSTED_TILL);
        SavingsAccountData account = buildAccountWithSummary(id, officeId, currencyCode, summary);
        SavingsAccountTransactionEnumData txType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(null, txType, null, id, "SA-" + id, POSTING_DATE, null,
                amount, null, null, false, null, false, null, null, POSTING_DATE);
        account.setSavingsAccountTransactionData(tx);
        return account;
    }

    private static SavingsAccountData buildAccountWithTwoTx(Long id, Long officeId, String currencyCode, BigDecimal interestAmount,
            BigDecimal taxAmount) {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(new CurrencyData(currencyCode), null, null, null, null, null,
                null, null, null, null, null, null, null, LAST_CALC_DATE, null, INTEREST_POSTED_TILL);
        SavingsAccountData account = buildAccountWithSummary(id, officeId, currencyCode, summary);
        SavingsAccountTransactionEnumData interestType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        SavingsAccountTransactionData interestTx = SavingsAccountTransactionData.create(null, interestType, null, id, "SA-" + id,
                POSTING_DATE, null, interestAmount, null, null, false, null, false, null, null, POSTING_DATE);
        account.setSavingsAccountTransactionData(interestTx);
        SavingsAccountTransactionEnumData taxType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.WITHHOLD_TAX.getValue().longValue(), SavingsAccountTransactionType.WITHHOLD_TAX.getCode(),
                SavingsAccountTransactionType.WITHHOLD_TAX.getValue().toString());
        SavingsAccountTransactionData taxTx = SavingsAccountTransactionData.create(null, taxType, null, id, "SA-" + id, POSTING_DATE, null,
                taxAmount, null, null, false, null, false, null, null, POSTING_DATE);
        account.setSavingsAccountTransactionData(taxTx);
        return account;
    }

    private static SavingsAccountData buildAccountNoTx(Long id, Long officeId, String currencyCode) {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(new CurrencyData(currencyCode), null, null, null, null, null,
                null, null, null, null, null, null, null, LAST_CALC_DATE, null, INTEREST_POSTED_TILL);
        return buildAccountWithSummary(id, officeId, currencyCode, summary);
    }

    private static SavingsAccountData buildAccountWithSummary(Long id, Long officeId, String currencyCode,
            SavingsAccountSummaryData summary) {
        CurrencyData currency = new CurrencyData(currencyCode);
        SavingsAccountData account = SavingsAccountData.instance(id, "SA-" + id, null, "EXT-" + id, null, null, null, null, null, null,
                null, null, null, null, null, null, currency, null, null, null, null, null, null, null, null, false, summary, false, null,
                null, false, null, false, null, null, null, null, false, null, null, false, null, null, null, null);
        ClientData client = new ClientData();
        client.setOfficeId(officeId);
        account.setClientData(client);
        return account;
    }
}
