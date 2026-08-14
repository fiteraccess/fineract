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
package org.apache.fineract.portfolio.savings.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeCommissionAllocationDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTransactionDTO;

public final class SavingsAccountingBridgeDataHelper {

    private SavingsAccountingBridgeDataHelper() {}

    public static SavingsAccountingBridgeDTO buildAccountingBridgeData(final SavingsAccount account,
            final Collection<SavingsAccountTransaction> newTransactions, final boolean isAccountTransfer) {
        final String currencyCode = account.getCurrency().getCode();
        final List<SavingsAccountingBridgeTransactionDTO> newSavingsTransactions = new ArrayList<>(newTransactions.size());
        for (final SavingsAccountTransaction transaction : newTransactions) {
            newSavingsTransactions.add(transaction.toAccountingBridgeDTO(currencyCode));
        }
        return new SavingsAccountingBridgeDTO(account.getId(), account.productId(), account.officeId(), currencyCode,
                account.savingsProduct().isCashBasedAccountingEnabled(), account.savingsProduct().isAccrualBasedAccountingEnabled(),
                isAccountTransfer, newSavingsTransactions);
    }

    public static SavingsAccountingBridgeDTO buildAccountingBridgeData(final SavingsAccount account,
            final SavingsAccountTransaction transaction, final ReferenceTransaction referenceTransaction, final boolean isAccountTransfer) {
        final SavingsAccountingBridgeDTO accountingBridgeData = buildAccountingBridgeData(account, List.of(transaction), isAccountTransfer);
        final ReferenceTransaction.CommissionBreakdown breakdown = referenceTransaction.breakdown();
        // AB-510: bills/airtime aggregator-scoped commission has no switch/bank-commission split — breakdown is
        // legitimately null there (only NIP switch-scoped commission requires and validates one).
        if (referenceTransaction.type().isCommission() && breakdown != null) {
            accountingBridgeData.getNewSavingsTransactions().get(0)
                    .setCommissionAllocation(new SavingsAccountingBridgeCommissionAllocationDTO(breakdown.switchFee().amount(),
                            breakdown.bankCommission().amount()));
        }
        return accountingBridgeData;
    }

    public static List<SavingsAccountTransaction> findNewTransactions(final SavingsAccount account, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean backdatedTxnsAllowedTill) {
        final List<SavingsAccountTransaction> transactions = backdatedTxnsAllowedTill
                ? account.getSavingsAccountTransactionsWithPivotConfig()
                : account.getTransactions();
        final List<SavingsAccountTransaction> newTransactions = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isReversed() && !existingReversedTransactionIds.contains(transaction.getId())) {
                newTransactions.add(transaction);
            } else if (!existingTransactionIds.contains(transaction.getId())) {
                newTransactions.add(transaction);
            }
        }
        return newTransactions;
    }
}
