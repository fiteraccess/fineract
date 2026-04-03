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

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.synapse.AccountCursorUpdate;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBatchPostingResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseInterestPostingBatch;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostingResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Operation;

@Slf4j
@RequiredArgsConstructor
public class SynapseInterestPostingService {

    private static final String ACCEPTED_STATUS = "ACCEPTED";

    private final SynapseInstructionMapper mapper;
    private final SynapseTransactionClient client;

    /**
     * Collects eligible interest-posting instructions from the given accounts,
     * sends them to Synapse in a single batch, and returns cursor updates for
     * accounts whose instructions all succeeded.
     * <p>
     * Zero-interest accounts (no eligible transactions) always receive cursor
     * updates since no Synapse call is needed for them.
     *
     * @param accounts    the accounts whose interest has been calculated
     * @param postingDate the date interest is being posted for
     * @return cursor updates for succeeded accounts + accepted/failed counts
     * @throws SynapsePostingException if the HTTP call itself fails
     */
    /**
     * Sends interest-posting instructions for a single account to Synapse.
     * Delegates to {@link #postInterestBatch} with a list of one.
     *
     * @param account     the account whose interest has been calculated
     * @param postingDate the date interest is being posted for
     * @return cursor updates + accepted/failed counts
     */
    public SynapsePostResult postInterestForAccount(SavingsAccountData account, LocalDate postingDate) {
        return postInterestBatch(List.of(account), postingDate);
    }

    public SynapsePostResult postInterestBatch(List<SavingsAccountData> accounts, LocalDate postingDate) {
        String batchId = UUID.randomUUID().toString();

        List<AccountCursorUpdate> allCursors = accounts.stream().map(this::toCursorUpdate).toList();
        List<SynapseTransactionInstruction> instructions = accounts.stream()
                .flatMap(acct -> toInstructions(acct, batchId)).toList();

        if (instructions.isEmpty()) {
            log.debug("Batch {}: no eligible instructions, skipping Synapse call", batchId);
            return new SynapsePostResult(allCursors, accounts.size(), 0);
        }

        SynapseInterestPostingBatch batch = SynapseInterestPostingBatch.builder()
                .batchId(batchId).postingDate(postingDate)
                .totalCount(instructions.size()).transactions(instructions).build();

        log.debug("Batch {}: sending {} instructions", batchId, instructions.size());

        SynapseBatchPostingResponse response = client.postBatch(batch);

        return filterByResponse(allCursors, instructions, response);
    }

    private AccountCursorUpdate toCursorUpdate(SavingsAccountData account) {
        SavingsAccountSummaryData summary = account.getSummary();
        LocalDate postedTill = Objects.requireNonNullElse(
                summary.getInterestPostedTillDate(), summary.getLastInterestCalculationDate());
        return new AccountCursorUpdate(account.getId(), postedTill, summary.getLastInterestCalculationDate());
    }

    private Stream<SynapseTransactionInstruction> toInstructions(SavingsAccountData account, String batchId) {
        return account.getSavingsAccountTransactionData().stream()
                .map(tx -> toInstruction(account, tx, batchId))
                .filter(Objects::nonNull);
    }

    private SynapseTransactionInstruction toInstruction(SavingsAccountData account, SavingsAccountTransactionData tx, String batchId) {
        if (tx.getId() == null && !MathUtil.isZero(tx.getAmount())) {
            return mapper.map(account, tx, Operation.POST, batchId);
        }
        if (tx.getId() != null && tx.isReversed()) {
            return mapper.map(account, tx, Operation.REVERSE, batchId);
        }
        return null;
    }

    private SynapsePostResult filterByResponse(List<AccountCursorUpdate> allCursors,
            List<SynapseTransactionInstruction> instructions, SynapseBatchPostingResponse response) {

        Set<String> failedTraceIds = response.getResults().stream()
                .filter(r -> !ACCEPTED_STATUS.equals(r.getStatus()))
                .map(SynapsePostingResult::getTraceId)
                .collect(Collectors.toSet());

        Set<Long> failedAccountIds = instructions.stream()
                .filter(i -> failedTraceIds.contains(i.getTraceId()))
                .map(SynapseTransactionInstruction::getSavingsAccountId)
                .collect(Collectors.toSet());

        if (failedAccountIds.isEmpty()) {
            return new SynapsePostResult(allCursors, allCursors.size(), 0);
        }

        log.warn("Batch had {} failed accounts: {}", failedAccountIds.size(), failedAccountIds);

        List<AccountCursorUpdate> survivingCursors = allCursors.stream()
                .filter(c -> !failedAccountIds.contains(c.getAccountId())).toList();

        return new SynapsePostResult(survivingCursors, survivingCursors.size(), failedAccountIds.size());
    }
}

