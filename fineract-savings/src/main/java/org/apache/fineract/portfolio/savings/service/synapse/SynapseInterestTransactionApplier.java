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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
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
            "WITHHOLD_TAX", SavingsAccountTransactionType.WITHHOLD_TAX);

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

        // 1. Deduplicate on traceId
        List<SavingsAccountTransaction> existing = transactionRepository.findByRefNo(traceId);
        for (SavingsAccountTransaction tx : existing) {
            if (tx.getSavingsAccount().getId().equals(account.getId()) && tx.isNotReversed()) {
                log.debug("Replay already exists for traceId={} on account={}", traceId, account.getId());
                return new ReplayResult(tx, true);
            }
        }

        // 2. Resolve transaction type
        SavingsAccountTransactionType type = resolveTransactionType(transactionType);

        // 3. Create transaction entity
        Money money = Money.of(account.getCurrency(), transactionAmount);
        SavingsAccountTransaction transaction = createTransaction(account, type, transactionDate, money);
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
            Money money) {
        return switch (type) {
            case INTEREST_POSTING -> SavingsAccountTransaction.interestPosting(account, account.office(), date, money, false);
            case OVERDRAFT_INTEREST -> SavingsAccountTransaction.overdraftInterest(account, account.office(), date, money, false);
            case WITHHOLD_TAX -> SavingsAccountTransaction.withHoldTax(account, account.office(), date, money, Collections.emptyMap());
            default -> throw new IllegalArgumentException("Unsupported replay transaction type: " + type);
        };
    }
}
