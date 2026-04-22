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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.synapse.AccountCursorUpdate;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Operation;

@Slf4j
@RequiredArgsConstructor
public class SynapseInterestPostingOutboxWriter {

    private final SynapseInstructionMapper mapper;
    private final SynapseOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Collects eligible interest-posting instructions from the given accounts, writes them to the outbox for
     * asynchronous dispatch, and returns cursor updates for all accounts.
     * <p>
     * Zero-interest accounts (no eligible transactions) always receive cursor updates since no outbox write is needed
     * for them.
     *
     * @param accounts
     *            the accounts whose interest has been calculated
     * @param postingDate
     *            the date interest is being posted for
     * @return cursor updates for all accounts + accepted/failed counts
     */
    /**
     * Sends interest-posting instructions for a single account to Synapse. Delegates to {@link #postInterestBatch} with
     * a list of one.
     *
     * @param account
     *            the account whose interest has been calculated
     * @param postingDate
     *            the date interest is being posted for
     * @return cursor updates + accepted/failed counts
     */
    public SynapsePostResult postInterestForAccount(SavingsAccountData account, LocalDate postingDate) {
        return postInterestBatch(List.of(account), postingDate);
    }

    public SynapsePostResult postInterestBatch(List<SavingsAccountData> accounts, LocalDate postingDate) {
        String batchId = UUID.randomUUID().toString();

        List<AccountCursorUpdate> allCursors = accounts.stream().map(this::toCursorUpdate).toList();
        List<SynapseTransactionInstruction> instructions = accounts.stream().flatMap(acct -> toInstructions(acct, batchId)).toList();

        if (instructions.isEmpty()) {
            log.debug("Batch {}: no eligible instructions, skipping outbox write", batchId);
            return new SynapsePostResult(allCursors, accounts.size(), 0);
        }

        writeToOutbox(batchId, instructions);

        log.debug("Batch {}: wrote {} instructions to outbox", batchId, instructions.size());
        return new SynapsePostResult(allCursors, allCursors.size(), 0);
    }

    private AccountCursorUpdate toCursorUpdate(SavingsAccountData account) {
        SavingsAccountSummaryData summary = account.getSummary();
        LocalDate postedTill = Objects.requireNonNullElse(summary.getInterestPostedTillDate(), summary.getLastInterestCalculationDate());
        return new AccountCursorUpdate(account.getId(), postedTill, summary.getLastInterestCalculationDate());
    }

    private Stream<SynapseTransactionInstruction> toInstructions(SavingsAccountData account, String batchId) {
        return account.getSavingsAccountTransactionData().stream().map(tx -> toInstruction(account, tx, batchId)).filter(Objects::nonNull);
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

    private void writeToOutbox(String batchId, List<SynapseTransactionInstruction> instructions) {
        List<OutboxEntry> entries = instructions.stream().map(instr -> {
            try {
                return OutboxEntry.builder().traceId(instr.getTraceId()).accountId(instr.getSavingsAccountId())
                        .officeId(instr.getOfficeId()).payload(objectMapper.writeValueAsString(instr)).build();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize instruction for traceId: " + instr.getTraceId(), e);
            }
        }).toList();
        outboxRepository.insertBatch("INTEREST_POSTING", batchId, entries);
    }
}
