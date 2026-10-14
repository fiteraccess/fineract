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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsProductCategory;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;

/**
 * Owns all replay business logic for interest posting transactions received from Synapse. Handles deduplication,
 * transaction type resolution, entity construction, and incremental balance updates.
 *
 * <p>
 * This class is intentionally NOT annotated with {@code @Service} — it is registered as a conditional bean in
 * {@code SavingsConfiguration} (only when {@code fineract.synapse.enabled=true}).
 */
@Slf4j
@RequiredArgsConstructor
public class SynapseInterestTransactionApplier {

    private static final Map<String, SavingsAccountTransactionType> TRANSACTION_TYPES = Map.of("INTEREST_POSTING",
            SavingsAccountTransactionType.INTEREST_POSTING, "OVERDRAFT_INTEREST", SavingsAccountTransactionType.OVERDRAFT_INTEREST,
            "WITHHOLD_TAX", SavingsAccountTransactionType.WITHHOLD_TAX, "GOAL_PENALTY", SavingsAccountTransactionType.GOAL_PENALTY,
            "GOAL_FORFEITURE", SavingsAccountTransactionType.GOAL_FORFEITURE);

    private final SavingsAccountTransactionRepository transactionRepository;
    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;

    public record ReplayResult(SavingsAccountTransaction transaction, boolean alreadyExists) {
    }

    /**
     * Replays a single interest posting transaction from Synapse.
     *
     * @param account
     *            the savings account to post against
     * @param transactionType
     *            one of INTEREST_POSTING, OVERDRAFT_INTEREST, WITHHOLD_TAX
     * @param transactionAmount
     *            the amount to post
     * @param transactionDate
     *            the date of the transaction
     * @param overdraftAmount
     *            the overdraft amount (only for OVERDRAFT_INTEREST)
     * @param traceId
     *            the Synapse trace ID used for idempotency (stored in ref_no)
     * @return a ReplayResult indicating whether the transaction was created or already existed
     */
    public ReplayResult replay(SavingsAccount account, String transactionType, BigDecimal transactionAmount, LocalDate transactionDate,
            BigDecimal overdraftAmount, String traceId) {
        return replay(account, transactionType, transactionAmount, transactionDate, overdraftAmount, traceId, false);
    }

    /**
     * As {@link #replay(SavingsAccount, String, BigDecimal, LocalDate, BigDecimal, String)}, keeping a manual posting
     * manual.
     */
    public ReplayResult replay(SavingsAccount account, String transactionType, BigDecimal transactionAmount, LocalDate transactionDate,
            BigDecimal overdraftAmount, String traceId, boolean manual) {

        // 1. Deduplicate on traceId, reversed or not: a late retry of a posting a correction has since reversed must
        // not book it again (AB-401).
        List<SavingsAccountTransaction> existing = transactionRepository.findByRefNo(traceId);
        for (SavingsAccountTransaction tx : existing) {
            if (tx.getSavingsAccount().getId().equals(account.getId())) {
                log.debug("Replay already exists for traceId={} on account={}", traceId, account.getId());
                return new ReplayResult(tx, true);
            }
        }

        // 2. Resolve transaction type
        SavingsAccountTransactionType type = resolveTransactionType(transactionType);

        if (type == SavingsAccountTransactionType.GOAL_PENALTY || type == SavingsAccountTransactionType.GOAL_FORFEITURE) {
            if (account.savingsProduct().getProductCategory() != SavingsProductCategory.GOAL || transactionAmount.signum() < 0) {
                throw new GeneralPlatformDomainRuleException("error.msg.goal.settlement.invalid", "Only goal interest may be forfeited");
            }
            var payable = account.getTransactions().stream().filter(tx -> !tx.isReversed() && !tx.isReversalTransaction())
                    .map(tx -> switch (tx.getTransactionType()) {
                        case ACCRUAL -> tx.getAmount(account.getCurrency()).getAmount();
                        case INTEREST_POSTING, GOAL_PENALTY, GOAL_FORFEITURE -> tx.getAmount(account.getCurrency()).getAmount().negate();
                        default -> BigDecimal.ZERO;
                    }).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (transactionAmount.compareTo(payable) > 0) {
                throw new GeneralPlatformDomainRuleException("error.msg.goal.settlement.exceeds.payable",
                        "Settlement exceeds accrued interest payable");
            }
            if (type == SavingsAccountTransactionType.GOAL_FORFEITURE) account.stopGoalAccrual();
        }
        // 3. Create transaction entity
        Money money = Money.of(account.getCurrency(), transactionAmount);
        SavingsAccountTransaction transaction = createTransaction(account, type, transactionDate, money, manual);
        transaction.setRefNo(traceId);

        // 4. Set overdraft amount if applicable
        if (type == SavingsAccountTransactionType.OVERDRAFT_INTEREST && overdraftAmount != null) {
            transaction.setOverdraftAmount(Money.of(account.getCurrency(), overdraftAmount));
        }

        // 5. Update account balances incrementally (updates accountBalance, totalInterestPosted,
        // totalOverdraftInterestDerived, totalWithholdTax as appropriate)
        account.addTransaction(transaction);
        account.getSummary().updateSummaryWithTransaction(account.getCurrency(), summaryWrapper, transaction);

        // 6. Set running balance to the new account balance
        transaction.setRunningBalance(Money.of(account.getCurrency(), account.getSummary().getAccountBalance()));

        return new ReplayResult(transaction, false);
    }

    /**
     * Replays a Synapse REVERSE: marks the posting Synapse has just reversed in its ledger as reversed here too and
     * takes it back out of the balances. A second replay of the same reversal finds it already reversed and changes
     * nothing.
     *
     * @param account
     *            the savings account the original posting belongs to
     * @param transactionType
     *            the original posting's type, one of INTEREST_POSTING, OVERDRAFT_INTEREST, WITHHOLD_TAX
     * @param originalTransactionId
     *            the Fineract id of the posting to reverse
     * @return the original posting, with {@code alreadyExists} true when it was already reversed
     */
    public ReplayResult reverse(SavingsAccount account, String transactionType, Long originalTransactionId) {
        if (originalTransactionId == null) {
            throw new PlatformApiDataValidationException(
                    List.of(ApiParameterError.parameterError("error.msg.savings.replay.originalTransactionId.required",
                            "A reversal replay needs the original transaction id", "originalTransactionId")));
        }
        SavingsAccountTransactionType type = resolveTransactionType(transactionType);
        SavingsAccountTransaction original = account.getTransactions().stream().filter(tx -> originalTransactionId.equals(tx.getId()))
                .findFirst()
                .orElseThrow(() -> new GeneralPlatformDomainRuleException("error.msg.savings.replay.original.not.found",
                        "Transaction " + originalTransactionId + " is not on savings account " + account.getId(), originalTransactionId,
                        account.getId()));
        if (original.getTransactionType() != type || original.isReversalTransaction()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.replay.original.type.mismatch",
                    "Transaction " + originalTransactionId + " is not a " + transactionType + " posting", originalTransactionId,
                    transactionType);
        }
        if (original.isReversed()) {
            log.debug("Reversal already replayed for transaction={} on account={}", originalTransactionId, account.getId());
            return new ReplayResult(original, true);
        }
        account.getSummary().updateSummaryWithReversal(account.getCurrency(), summaryWrapper, original);
        original.reverse();
        return new ReplayResult(original, false);
    }

    private SavingsAccountTransactionType resolveTransactionType(String transactionType) {
        SavingsAccountTransactionType type = TRANSACTION_TYPES.get(transactionType);
        if (type == null) {
            throw new PlatformApiDataValidationException(
                    List.of(ApiParameterError.parameterError("error.msg.savings.replay.transactionType.invalid",
                            "Unsupported replay transaction type: " + transactionType, "transactionType", transactionType)));
        }
        return type;
    }

    private SavingsAccountTransaction createTransaction(SavingsAccount account, SavingsAccountTransactionType type, LocalDate date,
            Money money, boolean manual) {
        return switch (type) {
            case INTEREST_POSTING -> SavingsAccountTransaction.interestPosting(account, account.office(), date, money, manual);
            case OVERDRAFT_INTEREST -> SavingsAccountTransaction.overdraftInterest(account, account.office(), date, money, manual);
            case GOAL_PENALTY, GOAL_FORFEITURE -> SavingsAccountTransaction.goalInterestSettlement(account, type, date, money, null);
            case WITHHOLD_TAX -> {
                if (account.getTaxGroup() == null && (account.savingsProduct() == null
                        || account.savingsProduct().getProductCategory() != SavingsProductCategory.GOAL)) {
                    yield SavingsAccountTransaction.withHoldTax(account, account.office(), date, money, java.util.Collections.emptyMap());
                }
                if (account.getTaxGroup() == null) {
                    throw new GeneralPlatformDomainRuleException("error.msg.savings.tax.mapping.missing",
                            "Withholding tax requires a tax group");
                }
                var split = new java.util.LinkedHashMap<org.apache.fineract.portfolio.tax.domain.TaxComponent, BigDecimal>();
                for (var mapping : account.getTaxGroup().getTaxGroupMappings()) {
                    if (!mapping.occursOnDayFromAndUpToAndIncluding(date)) continue;
                    var component = mapping.getTaxComponent();
                    var percentage = component.getApplicablePercentage(date);
                    if (percentage != null && percentage.signum() > 0) split.put(component, percentage);
                }
                var calculated = split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (calculated.signum() <= 0) {
                    throw new GeneralPlatformDomainRuleException("error.msg.savings.tax.mapping.missing",
                            "No active withholding tax component");
                }
                var remaining = money.getAmount();
                var allocated = new java.util.LinkedHashMap<org.apache.fineract.portfolio.tax.domain.TaxComponent, BigDecimal>();
                var entries = split.entrySet().stream().filter(e -> e.getValue().signum() > 0).toList();
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    var part = i == entries.size() - 1 ? remaining
                            : money.getAmount().multiply(entry.getValue()).divide(calculated, money.getAmount().scale(),
                                    java.math.RoundingMode.HALF_UP);
                    part = part.min(remaining);
                    allocated.put(entry.getKey(), part);
                    remaining = remaining.subtract(part);
                }
                yield SavingsAccountTransaction.withHoldTax(account, account.office(), date, money, allocated);
            }
            default -> throw new IllegalArgumentException("Unsupported replay transaction type: " + type);
        };
    }
}
