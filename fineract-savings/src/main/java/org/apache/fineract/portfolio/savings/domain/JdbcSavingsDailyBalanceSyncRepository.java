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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed implementation of {@link SavingsDailyBalanceSyncRepository}. All SQL is Postgres-specific; see §4.1/§10.3
 * of the Daily Balance optimization plan.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcSavingsDailyBalanceSyncRepository implements SavingsDailyBalanceSyncRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final String SELECT_AND_LOCK_WATERMARK_SQL = """
            SELECT last_synced_at
            FROM m_savings_daily_balance_sync_state
            WHERE id = 1
            FOR UPDATE
            """;

    private static final String UPDATE_WATERMARK_SQL = """
            UPDATE m_savings_daily_balance_sync_state
            SET last_synced_at = :newValue
            WHERE id = 1
            """;

    // Filters:
    // - is_reversed = false: skip txns that were undone.
    // - original_transaction_id IS NULL: skip bookkeeping reversal records. The is_reversal flag on these rows
    // is unreliable in practice (Fineract's reversal flow persists is_reversal=false on most accounts; only
    // rarely does it stick as true), but original_transaction_id is set exclusively by the reversal()
    // factory and is reliable. Without this filter the reversal record's polluted running_balance_derived
    // (rewritten by upstream recalc as if it were a regular deposit) would clobber the snapshot.
    // - running_balance_derived IS NOT NULL: legacy rows / certain txn types never get a value set, and the
    // snapshot table's end_of_day_balance is NOT NULL.
    private static final String SYNC_FROM_TRANSACTIONS_SQL = """
            INSERT INTO m_savings_account_daily_balance (savings_account_id, balance_date, end_of_day_balance)
            SELECT DISTINCT ON (savings_account_id, transaction_date)
                   savings_account_id, transaction_date, running_balance_derived
            FROM m_savings_account_transaction
            WHERE last_modified_on_utc >  :watermark
              AND last_modified_on_utc <= :upTo
              AND is_reversed = false
              AND original_transaction_id IS NULL
              AND running_balance_derived IS NOT NULL
            ORDER BY savings_account_id, transaction_date, id DESC
            ON CONFLICT (savings_account_id, balance_date)
            DO UPDATE SET end_of_day_balance = EXCLUDED.end_of_day_balance
            WHERE m_savings_account_daily_balance.end_of_day_balance
                  IS DISTINCT FROM EXCLUDED.end_of_day_balance
            """;

    private static final String ENQUEUE_DIRTY_SQL = """
            INSERT INTO m_savings_daily_balance_dirty (savings_account_id, balance_date)
            VALUES (:accountId, :balanceDate)
            ON CONFLICT (savings_account_id, balance_date) DO NOTHING
            """;

    private static final String COUNT_DIRTY_SQL = "SELECT COUNT(*) FROM m_savings_daily_balance_dirty";

    private static final String DRAIN_DELETE_EMPTY_DAYS_SQL = """
            DELETE FROM m_savings_account_daily_balance s
            USING m_savings_daily_balance_dirty d
            WHERE s.savings_account_id = d.savings_account_id
              AND s.balance_date       = d.balance_date
              AND NOT EXISTS (
                  SELECT 1
                  FROM m_savings_account_transaction t
                  WHERE t.savings_account_id = d.savings_account_id
                    AND t.transaction_date   = d.balance_date
                    AND t.is_reversed = false
                    AND t.original_transaction_id IS NULL
              )
            """;

    private static final String DRAIN_UPSERT_SQL = """
            INSERT INTO m_savings_account_daily_balance (savings_account_id, balance_date, end_of_day_balance)
            SELECT d.savings_account_id, d.balance_date, t.running_balance_derived
            FROM m_savings_daily_balance_dirty d
            JOIN LATERAL (
                SELECT running_balance_derived
                FROM m_savings_account_transaction
                WHERE savings_account_id = d.savings_account_id
                  AND transaction_date   = d.balance_date
                  AND is_reversed = false
                  AND original_transaction_id IS NULL
                  AND running_balance_derived IS NOT NULL
                ORDER BY id DESC
                LIMIT 1
            ) t ON TRUE
            ON CONFLICT (savings_account_id, balance_date)
            DO UPDATE SET end_of_day_balance = EXCLUDED.end_of_day_balance
            WHERE m_savings_account_daily_balance.end_of_day_balance
                  IS DISTINCT FROM EXCLUDED.end_of_day_balance
            """;

    private static final String TRUNCATE_DIRTY_SQL = "TRUNCATE m_savings_daily_balance_dirty";

    @Override
    public OffsetDateTime readAndLockWatermark() {
        Timestamp ts = jdbcTemplate.queryForObject(SELECT_AND_LOCK_WATERMARK_SQL, Collections.emptyMap(), Timestamp.class);
        if (ts == null) {
            // Should not happen — singleton row is seeded by Liquibase. Defensive default to epoch UTC.
            log.warn("m_savings_daily_balance_sync_state.last_synced_at returned NULL; defaulting to epoch.");
            return OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        }
        return ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    @Override
    public void advanceWatermark(OffsetDateTime newValue) {
        Map<String, Object> params = new HashMap<>();
        params.put("newValue", Timestamp.from(newValue.toInstant()));
        int updated = jdbcTemplate.update(UPDATE_WATERMARK_SQL, params);
        if (updated != 1) {
            log.warn("advanceWatermark: expected 1 row updated, got {} (newValue={})", updated, newValue);
        }
    }

    @Override
    public int syncFromTransactions(OffsetDateTime watermark, OffsetDateTime upTo) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("watermark", Timestamp.from(watermark.toInstant()))
                .addValue("upTo", Timestamp.from(upTo.toInstant()));
        return jdbcTemplate.update(SYNC_FROM_TRANSACTIONS_SQL, params);
    }

    @Override
    public void enqueueDirty(Long savingsAccountId, LocalDate balanceDate) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("accountId", savingsAccountId).addValue("balanceDate",
                java.sql.Date.valueOf(balanceDate));
        jdbcTemplate.update(ENQUEUE_DIRTY_SQL, params);
    }

    @Override
    public int drainDirty() {
        Integer pending = jdbcTemplate.queryForObject(COUNT_DIRTY_SQL, Collections.emptyMap(), Integer.class);
        int total = pending == null ? 0 : pending;
        if (total == 0) {
            return 0;
        }
        jdbcTemplate.update(DRAIN_DELETE_EMPTY_DAYS_SQL, Collections.emptyMap());
        jdbcTemplate.update(DRAIN_UPSERT_SQL, Collections.emptyMap());
        jdbcTemplate.getJdbcTemplate().execute(TRUNCATE_DIRTY_SQL);
        return total;
    }
}
