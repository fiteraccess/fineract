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

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Repository for the savings daily-balance sync subsystem.
 *
 * <p>
 * Backs the hourly batch that derives {@code m_savings_account_daily_balance} rows from
 * {@code m_savings_account_transaction.running_balance_derived}. All timestamps are {@link OffsetDateTime} (UTC) to
 * match the {@code last_modified_on_utc} column on auditable entities.
 * </p>
 */
public interface SavingsDailyBalanceSyncRepository {

    /**
     * Reads and locks the singleton watermark row in {@code m_savings_daily_balance_sync_state}.
     * <p>
     * Uses {@code SELECT ... FOR UPDATE} so concurrent batch runs serialize cleanly. The lock is released on
     * transaction commit.
     * </p>
     *
     * @return the current watermark
     */
    OffsetDateTime readAndLockWatermark();

    /**
     * Advance the watermark to {@code newValue}. Must be called within the same transaction as
     * {@link #readAndLockWatermark()}.
     */
    void advanceWatermark(OffsetDateTime newValue);

    /**
     * Pass 1: bulk UPSERT into {@code m_savings_account_daily_balance} from the latest non-reversed transaction's
     * {@code running_balance_derived} for every account/date whose transaction was modified after {@code watermark} and
     * up to {@code upTo}.
     *
     * @param watermark
     *            exclusive lower bound on {@code last_modified_on_utc}
     * @param upTo
     *            inclusive upper bound on {@code last_modified_on_utc}
     * @return number of rows affected
     */
    int syncFromTransactions(OffsetDateTime watermark, OffsetDateTime upTo);

    /**
     * Pass 2a: enqueue an {@code (account, date)} pair into {@code m_savings_daily_balance_dirty}. Idempotent — uses
     * {@code ON CONFLICT DO NOTHING}.
     */
    void enqueueDirty(Long savingsAccountId, LocalDate balanceDate);

    /**
     * Pass 2b: drain the dirty set. For each enqueued {@code (account, date)}: DELETE the snapshot row if no
     * non-reversed transaction remains on that date; otherwise UPSERT it from the latest remaining transaction's
     * {@code running_balance_derived}. Truncates the dirty table on success — all three steps run in a single DB
     * transaction.
     *
     * @return number of dirty rows drained
     */
    int drainDirty();
}
