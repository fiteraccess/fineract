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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargePaidBy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;

/**
 * Replays charge posting transactions from Synapse. Handles deduplication and entity construction.
 */
@Slf4j
@RequiredArgsConstructor
public class SynapseChargeTransactionApplier {

    private final SavingsAccountTransactionRepository transactionRepository;
    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;

    public record ReplayResult(SavingsAccountTransaction transaction, boolean alreadyExists) {
    }

    public ReplayResult replay(SavingsAccount account, BigDecimal transactionAmount, LocalDate transactionDate, Long savingsAccountChargeId,
            String traceId) {

        // 1. Deduplicate on traceId
        List<SavingsAccountTransaction> existing = transactionRepository.findByRefNo(traceId);
        for (SavingsAccountTransaction tx : existing) {
            if (tx.getSavingsAccount().getId().equals(account.getId()) && tx.isNotReversed()) {
                log.debug("Charge replay already exists for traceId={} on account={}", traceId, account.getId());
                return new ReplayResult(tx, true);
            }
        }

        // 2. Create PAY_CHARGE transaction
        Money money = Money.of(account.getCurrency(), transactionAmount);
        SavingsAccountTransaction transaction = SavingsAccountTransaction.charge(account, account.office(), transactionDate, money);
        transaction.setRefNo(traceId);

        // 3. Link to the savings account charge
        SavingsAccountCharge charge = findCharge(account, savingsAccountChargeId);
        SavingsAccountChargePaidBy paidBy = SavingsAccountChargePaidBy.instance(transaction, charge, transactionAmount);
        transaction.getSavingsAccountChargesPaid().add(paidBy);

        // 4. Update account balances incrementally
        account.addTransaction(transaction);
        account.getSummary().updateSummaryWithTransaction(account.getCurrency(), summaryWrapper, transaction);

        // 5. Set running balance
        transaction.setRunningBalance(Money.of(account.getCurrency(), account.getSummary().getAccountBalance()));

        return new ReplayResult(transaction, false);
    }

    private SavingsAccountCharge findCharge(SavingsAccount account, Long savingsAccountChargeId) {
        return account.charges().stream().filter(c -> c.getId().equals(savingsAccountChargeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "SavingsAccountCharge not found: " + savingsAccountChargeId + " on account " + account.getId()));
    }
}
