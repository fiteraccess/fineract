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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostingResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SynapseInterestPostingServiceTest {

    private final SynapseInstructionMapper mapper = new SynapseInstructionMapper();
    private final SynapseTransactionClient client = mock(SynapseTransactionClient.class);
    private final SynapseInterestPostingService service = new SynapseInterestPostingService(mapper, client);

    private static final LocalDate POSTING_DATE = LocalDate.of(2026, 3, 20);
    private static final LocalDate INTEREST_POSTED_TILL = LocalDate.of(2026, 3, 20);
    private static final LocalDate LAST_CALC_DATE = LocalDate.of(2026, 3, 20);

    @Test
    void happyPathThreeAccountsAllAccepted() {
        SavingsAccountData acct1 = buildAccountWithInterestTx(1L, 10L, "NGN", new BigDecimal("100.00"));
        SavingsAccountData acct2 = buildAccountWithInterestTx(2L, 10L, "NGN", new BigDecimal("200.00"));
        SavingsAccountData acct3 = buildAccountWithInterestTx(3L, 10L, "NGN", new BigDecimal("50.00"));

        ArgumentCaptor<SynapseInterestPostingBatch> batchCaptor = ArgumentCaptor.forClass(SynapseInterestPostingBatch.class);
        when(client.postBatch(batchCaptor.capture())).thenAnswer(inv -> {
            SynapseInterestPostingBatch batch = inv.getArgument(0);
            List<SynapsePostingResult> results = batch.getTransactions().stream()
                    .map(tx -> new SynapsePostingResult(tx.getTraceId(), "ACCEPTED", null)).toList();
            return new SynapseBatchPostingResponse(batch.getBatchId(), 3, 0, results);
        });

        SynapsePostResult result = service.postInterestBatch(List.of(acct1, acct2, acct3), POSTING_DATE);

        assertThat(result.getAccepted()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(3);
        assertThat(result.getCursorUpdates()).extracting(AccountCursorUpdate::getAccountId).containsExactly(1L, 2L, 3L);

        SynapseInterestPostingBatch sentBatch = batchCaptor.getValue();
        assertThat(sentBatch.getTransactions()).hasSize(3);
        assertThat(sentBatch.getPostingDate()).isEqualTo(POSTING_DATE);
        assertThat(sentBatch.getTotalCount()).isEqualTo(3);
        String batchId = sentBatch.getBatchId();
        assertThat(sentBatch.getTransactions()).allSatisfy(tx -> assertThat(tx.getBatchId()).isEqualTo(batchId));
    }

    @Test
    void zeroInterestAccountsSkipHttpCallAndReturnAllCursors() {
        SavingsAccountData acct1 = buildAccountNoTx(1L, 10L, "NGN");
        SavingsAccountData acct2 = buildAccountNoTx(2L, 10L, "NGN");

        SynapsePostResult result = service.postInterestBatch(List.of(acct1, acct2), POSTING_DATE);

        verify(client, never()).postBatch(any());
        assertThat(result.getAccepted()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(2);
        assertThat(result.getCursorUpdates()).extracting(AccountCursorUpdate::getAccountId).containsExactly(1L, 2L);
        assertThat(result.getCursorUpdates()).extracting(AccountCursorUpdate::getInterestPostedTillDate)
                .containsExactly(LAST_CALC_DATE, LAST_CALC_DATE);
    }

    @Test
    void clientThrowsSynapsePostingExceptionPropagates() {
        SavingsAccountData acct = buildAccountWithInterestTx(1L, 10L, "NGN", new BigDecimal("100.00"));
        when(client.postBatch(any())).thenThrow(new SynapsePostingException("connection refused"));

        assertThatThrownBy(() -> service.postInterestBatch(List.of(acct), POSTING_DATE))
                .isInstanceOf(SynapsePostingException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void partialSuccessAccountWithRejectedInstructionExcludedFromCursors() {
        SavingsAccountData acct1 = buildAccountWithTwoTx(1L, 10L, "NGN",
                new BigDecimal("100.00"), new BigDecimal("10.00"));
        SavingsAccountData acct2 = buildAccountWithInterestTx(2L, 10L, "NGN", new BigDecimal("50.00"));

        when(client.postBatch(any())).thenAnswer(inv -> {
            SynapseInterestPostingBatch batch = inv.getArgument(0);
            List<SynapsePostingResult> results = List.of(
                    new SynapsePostingResult(batch.getTransactions().get(0).getTraceId(), "ACCEPTED", null),
                    new SynapsePostingResult(batch.getTransactions().get(1).getTraceId(), "REJECTED", null),
                    new SynapsePostingResult(batch.getTransactions().get(2).getTraceId(), "ACCEPTED", null));
            return new SynapseBatchPostingResponse(batch.getBatchId(), 2, 1, results);
        });

        SynapsePostResult result = service.postInterestBatch(List.of(acct1, acct2), POSTING_DATE);

        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getCursorUpdates()).hasSize(1);
        assertThat(result.getCursorUpdates().get(0).getAccountId()).isEqualTo(2L);
    }

    @Test
    void allInstructionsForAccountAcceptedCursorIncluded() {
        SavingsAccountData acct = buildAccountWithTwoTx(1L, 10L, "NGN",
                new BigDecimal("100.00"), new BigDecimal("10.00"));

        when(client.postBatch(any())).thenAnswer(inv -> {
            SynapseInterestPostingBatch batch = inv.getArgument(0);
            List<SynapsePostingResult> results = batch.getTransactions().stream()
                    .map(tx -> new SynapsePostingResult(tx.getTraceId(), "ACCEPTED", null)).toList();
            return new SynapseBatchPostingResponse(batch.getBatchId(), 2, 0, results);
        });

        SynapsePostResult result = service.postInterestBatch(List.of(acct), POSTING_DATE);

        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getCursorUpdates()).hasSize(1);
        assertThat(result.getCursorUpdates().get(0).getAccountId()).isEqualTo(1L);
        assertThat(result.getCursorUpdates().get(0).getInterestPostedTillDate()).isEqualTo(INTEREST_POSTED_TILL);
        assertThat(result.getCursorUpdates().get(0).getLastInterestCalculationDate()).isEqualTo(LAST_CALC_DATE);
    }

    @Test
    void batchIdConsistentAcrossAllInstructions() {
        SavingsAccountData acct1 = buildAccountWithInterestTx(1L, 10L, "NGN", new BigDecimal("100.00"));
        SavingsAccountData acct2 = buildAccountWithInterestTx(2L, 10L, "NGN", new BigDecimal("200.00"));

        ArgumentCaptor<SynapseInterestPostingBatch> batchCaptor = ArgumentCaptor.forClass(SynapseInterestPostingBatch.class);
        when(client.postBatch(batchCaptor.capture())).thenAnswer(inv -> {
            SynapseInterestPostingBatch batch = inv.getArgument(0);
            List<SynapsePostingResult> results = batch.getTransactions().stream()
                    .map(tx -> new SynapsePostingResult(tx.getTraceId(), "ACCEPTED", null)).toList();
            return new SynapseBatchPostingResponse(batch.getBatchId(), 2, 0, results);
        });

        service.postInterestBatch(List.of(acct1, acct2), POSTING_DATE);

        SynapseInterestPostingBatch batch = batchCaptor.getValue();
        String batchId = batch.getBatchId();
        assertThat(batchId).isNotNull();
        assertThat(batch.getTransactions().get(0).getBatchId()).isEqualTo(batchId);
        assertThat(batch.getTransactions().get(1).getBatchId()).isEqualTo(batchId);
    }

    @Test
    void cursorFallsBackToLastCalcDateWhenInterestPostedTillDateIsNull() {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(
                new CurrencyData("NGN"), null, null, null, null, null, null, null, null, null,
                null, null, null, LocalDate.of(2026, 3, 15), null, null);
        SavingsAccountData acct = buildAccountWithSummary(1L, 10L, "NGN", summary);

        SynapsePostResult result = service.postInterestBatch(List.of(acct), POSTING_DATE);

        verify(client, never()).postBatch(any());
        assertThat(result.getCursorUpdates()).hasSize(1);
        assertThat(result.getCursorUpdates().get(0).getInterestPostedTillDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(result.getCursorUpdates().get(0).getLastInterestCalculationDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void zeroAmountTransactionsAreNotSentToSynapse() {
        SavingsAccountData acct = buildAccountWithInterestTx(1L, 10L, "NGN", BigDecimal.ZERO);

        SynapsePostResult result = service.postInterestBatch(List.of(acct), POSTING_DATE);

        verify(client, never()).postBatch(any());
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getCursorUpdates()).hasSize(1);
    }

    @Test
    void reversedTransactionWithIdSentAsReverseOperation() {
        SavingsAccountData acct = buildAccountNoTx(1L, 10L, "NGN");
        SavingsAccountTransactionEnumData txType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(
                99L, txType, null, 1L, "SA-1", POSTING_DATE, null,
                new BigDecimal("75.00"), null, null, false, null,
                false, null, null, POSTING_DATE);
        tx.reverse();
        acct.setSavingsAccountTransactionData(tx);

        ArgumentCaptor<SynapseInterestPostingBatch> batchCaptor = ArgumentCaptor.forClass(SynapseInterestPostingBatch.class);
        when(client.postBatch(batchCaptor.capture())).thenAnswer(inv -> {
            SynapseInterestPostingBatch batch = inv.getArgument(0);
            List<SynapsePostingResult> results = batch.getTransactions().stream()
                    .map(t -> new SynapsePostingResult(t.getTraceId(), "ACCEPTED", null)).toList();
            return new SynapseBatchPostingResponse(batch.getBatchId(), 1, 0, results);
        });

        SynapsePostResult result = service.postInterestBatch(List.of(acct), POSTING_DATE);

        assertThat(result.getAccepted()).isEqualTo(1);
        SynapseInterestPostingBatch batch = batchCaptor.getValue();
        assertThat(batch.getTransactions()).hasSize(1);
        assertThat(batch.getTransactions().get(0).getOperation().name()).isEqualTo("REVERSE");
    }

    // --- account builders ---

    private static SavingsAccountData buildAccountWithInterestTx(Long id, Long officeId, String currencyCode, BigDecimal amount) {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(
                new CurrencyData(currencyCode), null, null, null, null, null, null, null, null, null,
                null, null, null, LAST_CALC_DATE, null, INTEREST_POSTED_TILL);
        SavingsAccountData account = buildAccountWithSummary(id, officeId, currencyCode, summary);
        SavingsAccountTransactionEnumData txType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(
                null, txType, null, id, "SA-" + id, POSTING_DATE, null,
                amount, null, null, false, null, false, null, null, POSTING_DATE);
        account.setSavingsAccountTransactionData(tx);
        return account;
    }

    private static SavingsAccountData buildAccountWithTwoTx(Long id, Long officeId, String currencyCode,
            BigDecimal interestAmount, BigDecimal taxAmount) {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(
                new CurrencyData(currencyCode), null, null, null, null, null, null, null, null, null,
                null, null, null, LAST_CALC_DATE, null, INTEREST_POSTED_TILL);
        SavingsAccountData account = buildAccountWithSummary(id, officeId, currencyCode, summary);
        SavingsAccountTransactionEnumData interestType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().longValue(),
                SavingsAccountTransactionType.INTEREST_POSTING.getCode(),
                SavingsAccountTransactionType.INTEREST_POSTING.getValue().toString());
        SavingsAccountTransactionData interestTx = SavingsAccountTransactionData.create(
                null, interestType, null, id, "SA-" + id, POSTING_DATE, null,
                interestAmount, null, null, false, null, false, null, null, POSTING_DATE);
        account.setSavingsAccountTransactionData(interestTx);
        SavingsAccountTransactionEnumData taxType = new SavingsAccountTransactionEnumData(
                SavingsAccountTransactionType.WITHHOLD_TAX.getValue().longValue(),
                SavingsAccountTransactionType.WITHHOLD_TAX.getCode(),
                SavingsAccountTransactionType.WITHHOLD_TAX.getValue().toString());
        SavingsAccountTransactionData taxTx = SavingsAccountTransactionData.create(
                null, taxType, null, id, "SA-" + id, POSTING_DATE, null,
                taxAmount, null, null, false, null, false, null, null, POSTING_DATE);
        account.setSavingsAccountTransactionData(taxTx);
        return account;
    }

    private static SavingsAccountData buildAccountNoTx(Long id, Long officeId, String currencyCode) {
        SavingsAccountSummaryData summary = new SavingsAccountSummaryData(
                new CurrencyData(currencyCode), null, null, null, null, null, null, null, null, null,
                null, null, null, LAST_CALC_DATE, null, INTEREST_POSTED_TILL);
        return buildAccountWithSummary(id, officeId, currencyCode, summary);
    }

    private static SavingsAccountData buildAccountWithSummary(Long id, Long officeId, String currencyCode,
            SavingsAccountSummaryData summary) {
        CurrencyData currency = new CurrencyData(currencyCode);
        SavingsAccountData account = SavingsAccountData.instance(id, "SA-" + id, null, "EXT-" + id,
                null, null, null, null, null, null, null, null,
                null, null, null, null, currency, null,
                null, null, null, null,
                null, null, null, false, summary, false,
                null, null, false, null, false, null,
                null, null, null, false, null,
                null, false, null, null, null, null);
        ClientData client = new ClientData();
        client.setOfficeId(officeId);
        account.setClientData(client);
        return account;
    }
}
