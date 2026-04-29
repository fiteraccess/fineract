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
package org.apache.fineract.portfolio.savings.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Service for maintaining daily balance snapshots for savings accounts. Supports O(1) snapshot updates for current-day
 * transactions and O(days_with_snapshots) updates for backdated transactions.
 *
 * @deprecated Replaced by {@link org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService}, which
 *             derives snapshots from {@code m_savings_account_transaction.running_balance_derived} on an hourly batch.
 *             Synchronous callers no longer maintain the snapshot table; this interface is kept only to allow staged
 *             removal across releases. New code MUST NOT depend on it.
 */
@Deprecated(forRemoval = true)
public interface DailyBalanceSnapshotService {

    /**
     * Updates the daily balance snapshot for a savings account on the given transaction date. If a snapshot for today
     * exists, updates it with the new balance. Otherwise creates a new snapshot.
     *
     * @param savingsAccountId
     *            the savings account ID
     * @param transactionDate
     *            the date of the transaction (typically today for optimized path)
     * @param newAccountBalance
     *            the account balance after the transaction
     * @deprecated see {@link DailyBalanceSnapshotService} class-level Javadoc; use
     *             {@link org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService#syncNow()}.
     */
    @Deprecated(forRemoval = true)
    void updateSnapshot(Long savingsAccountId, LocalDate transactionDate, BigDecimal newAccountBalance);

    /**
     * Handles a backdated transaction by updating the snapshot for the backdated date and cascading the balance delta
     * forward to all subsequent snapshots. This is O(days_with_snapshots) not O(total_transactions).
     *
     * @param savingsAccountId
     *            the savings account ID
     * @param backdatedDate
     *            the date of the backdated transaction
     * @param delta
     *            the balance change (positive for deposits, negative for withdrawals)
     * @param newBalanceOnDate
     *            the new end-of-day balance on the backdated date
     * @deprecated see {@link DailyBalanceSnapshotService} class-level Javadoc; use
     *             {@link org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService#syncNow()}.
     */
    @Deprecated(forRemoval = true)
    void handleBackdatedTransaction(Long savingsAccountId, LocalDate backdatedDate, BigDecimal delta, BigDecimal newBalanceOnDate);
}
