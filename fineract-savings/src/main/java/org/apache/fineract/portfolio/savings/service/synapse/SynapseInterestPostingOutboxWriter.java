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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.synapse.AccountCursorUpdate;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.data.synapse.SynapsePostResult;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Operation;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository;

@Slf4j
@RequiredArgsConstructor
public class SynapseInterestPostingOutboxWriter {

    private final SynapseInstructionMapper mapper;
    private final SynapseOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final SavingsInterestForfeitureRepository forfeitureRepository;
    private final ConfigurationDomainService configurationDomainService;

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
     * @return cursor updates + accepted/failed counts
     */
    public SynapsePostResult postInterestForAccount(SavingsAccountData account) {
        return postInterestBatch(List.of(account));
    }

    public SynapsePostResult postInterestBatch(List<SavingsAccountData> accounts) {
        String batchId = UUID.randomUUID().toString();

        final ForfeitedPeriods forfeited = forfeitedPeriodsOf(accounts);
        List<AccountCursorUpdate> allCursors = accounts.stream().map(acct -> toCursorUpdate(acct, forfeited)).toList();
        List<SynapseTransactionInstruction> instructions = accounts.stream().flatMap(acct -> toInstructions(acct, batchId, forfeited))
                .toList();

        if (instructions.isEmpty()) {
            log.debug("Batch {}: no eligible instructions, skipping outbox write", batchId);
            return new SynapsePostResult(allCursors, accounts.size(), 0);
        }

        writeToOutbox(batchId, instructions);

        log.debug("Batch {}: wrote {} instructions to outbox", batchId, instructions.size());
        return new SynapsePostResult(allCursors, allCursors.size(), 0);
    }

    /**
     * AB-566: the forfeited months of the page's accounts that have a new posting, in one query. A Regular Savings
     * month that closed forfeited gets neither its interest nor its withholding tax.
     */
    private ForfeitedPeriods forfeitedPeriodsOf(final List<SavingsAccountData> accounts) {
        final Set<Long> accountIds = new HashSet<>();
        LocalDate earliestPosting = null;
        for (SavingsAccountData account : accounts) {
            for (SavingsAccountTransactionData tx : account.getSavingsAccountTransactionData()) {
                if (ForfeitedPeriods.isNewForfeitablePosting(tx)) {
                    accountIds.add(account.getId());
                    earliestPosting = earliestPosting == null || tx.getTransactionDate().isBefore(earliestPosting) ? tx.getTransactionDate()
                            : earliestPosting;
                }
            }
        }
        if (accountIds.isEmpty()) {
            return ForfeitedPeriods.NONE;
        }
        final boolean postingAtPeriodEnd = configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd();
        final LocalDate fromMonth = ForfeitedPeriods.periodMonth(earliestPosting, postingAtPeriodEnd);
        return new ForfeitedPeriods(forfeitureRepository.findForfeitedMonths(accountIds, fromMonth), postingAtPeriodEnd);
    }

    private record ForfeitedPeriods(Map<Long, Set<LocalDate>> monthsByAccount, boolean postingAtPeriodEnd) {

        static final ForfeitedPeriods NONE = new ForfeitedPeriods(Map.of(), false);

        static boolean isNewForfeitablePosting(final SavingsAccountTransactionData tx) {
            return tx.getId() == null && tx.getTransactionType() != null
                    && (tx.getTransactionType().isInterestPosting() || tx.getTransactionType().isWithholdTax());
        }

        /** A posting at the period end is dated on the period's last day, otherwise on the day after it. */
        static LocalDate periodMonth(final LocalDate postingDate, final boolean postingAtPeriodEnd) {
            return (postingAtPeriodEnd ? postingDate : postingDate.minusDays(1)).withDayOfMonth(1);
        }

        boolean covers(final Long accountId, final SavingsAccountTransactionData tx) {
            final Set<LocalDate> months = monthsByAccount.get(accountId);
            return months != null && isNewForfeitablePosting(tx)
                    && months.contains(periodMonth(tx.getTransactionDate(), postingAtPeriodEnd));
        }
    }

    private AccountCursorUpdate toCursorUpdate(SavingsAccountData account, ForfeitedPeriods forfeited) {
        SavingsAccountSummaryData summary = account.getSummary();
        // AB-401: never fall back to the calculation date; a run that posts nothing must not move the posted-till date,
        // or the next posting starts after it and drops the interest earned before it.
        LocalDate postedTill = summary.getInterestPostedTillDate() != null ? summary.getInterestPostedTillDate()
                : summary.getPrevInterestPostedTillDate();
        // AB-566: a forfeited month is settled by not paying it, so the cursor moves past it or the next run re-posts
        // it.
        for (SavingsAccountTransactionData tx : account.getSavingsAccountTransactionData()) {
            if (forfeited.covers(account.getId(), tx) && (postedTill == null || tx.getTransactionDate().isAfter(postedTill))) {
                postedTill = tx.getTransactionDate();
            }
        }
        return new AccountCursorUpdate(account.getId(), postedTill, summary.getLastInterestCalculationDate());
    }

    private Stream<SynapseTransactionInstruction> toInstructions(SavingsAccountData account, String batchId, ForfeitedPeriods forfeited) {
        return account.getSavingsAccountTransactionData().stream().filter(tx -> !forfeited.covers(account.getId(), tx))
                .map(tx -> toInstruction(account, tx, batchId)).filter(Objects::nonNull);
    }

    private SynapseTransactionInstruction toInstruction(SavingsAccountData account, SavingsAccountTransactionData tx, String batchId) {
        // A deposit, withdrawal or levy reversed earlier sits in the same window; it is not this job's to replay.
        if (!mapper.supports(tx.getTransactionType())) {
            return null;
        }
        if (tx.getId() == null && !MathUtil.isZero(tx.getAmount())) {
            return mapper.map(account, tx, Operation.POST, batchId);
        }
        // Only a posting this run reversed: one loaded already reversed was reversed in Synapse when that happened.
        if (tx.getId() != null && tx.isReversedInRun()) {
            return mapper.map(account, tx, Operation.REVERSE, batchId);
        }
        return null;
    }

    private void writeToOutbox(String batchId, List<SynapseTransactionInstruction> instructions) {
        List<OutboxEntry> entries = instructions.stream().map(instr -> {
            try {
                return OutboxEntry.builder().traceId(instr.getTraceId()).accountId(instr.getSavingsAccountId())
                        .officeId(instr.getOfficeId()).payload(objectMapper.writeValueAsString(instr)).dedupKey(dedupKey(instr)).build();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize instruction for traceId: " + instr.getTraceId(), e);
            }
        }).toList();
        outboxRepository.insertBatch("INTEREST_POSTING", batchId, entries);
    }

    /**
     * What makes two instructions the same correction: a REVERSE undoes one Fineract posting; a POST books its type for
     * one value date, of which an account carries at most one live posting.
     */
    static String dedupKey(SynapseTransactionInstruction instr) {
        if (instr.getOperation() == Operation.REVERSE) {
            return "REVERSE:" + instr.getOriginalTransactionId();
        }
        return "POST:" + instr.getTransactionType() + ":" + instr.getTransactionDate();
    }
}
