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

    // Pass 1 does not copy balances itself. It only records WHICH (account, date) pairs had a transaction touched in
    // the
    // window, and the drain then re-derives each of those dates from the rows that still stand. Copying straight from
    // the touched rows was wrong in two ways: a touched row is not necessarily the last standing row of its date, and
    // a row that was reversed in the window was skipped altogether, so a date it emptied kept a stale snapshot row.
    //
    // Two statements, one per partial index on last_modified_on_utc (standing rows, reversed rows).
    // - original_transaction_id IS NULL / is_reversal = false: skip reversal mirror rows. Older data carries mirrors
    // with is_reversal = false, so both markers are checked.
    private static final String ENQUEUE_TOUCHED_STANDING_SQL = """
            INSERT INTO m_savings_daily_balance_dirty (savings_account_id, balance_date)
            SELECT DISTINCT savings_account_id, transaction_date
            FROM m_savings_account_transaction
            WHERE last_modified_on_utc >  :watermark
              AND last_modified_on_utc <= :upTo
              AND is_reversed = false
            ON CONFLICT (savings_account_id, balance_date) DO NOTHING
            """;

    private static final String ENQUEUE_TOUCHED_REVERSED_SQL = """
            INSERT INTO m_savings_daily_balance_dirty (savings_account_id, balance_date)
            SELECT DISTINCT savings_account_id, transaction_date
            FROM m_savings_account_transaction
            WHERE last_modified_on_utc >  :watermark
              AND last_modified_on_utc <= :upTo
              AND is_reversed = true
            ON CONFLICT (savings_account_id, balance_date) DO NOTHING
            """;

    private static final String ENQUEUE_DIRTY_SQL = """
            INSERT INTO m_savings_daily_balance_dirty (savings_account_id, balance_date)
            VALUES (:accountId, :balanceDate)
            ON CONFLICT (savings_account_id, balance_date) DO NOTHING
            """;

    // One statement per batch, so each batch is taken, applied and cleared against a single snapshot of the dirty set.
    // The dirty rows are consumed by the DELETE ... RETURNING itself; a pair enqueued by a concurrent transaction after
    // the statement started is simply not part of it and waits for a later batch. (The previous TRUNCATE dropped such
    // rows unprocessed.) SKIP LOCKED keeps two drains from waiting on each other.
    // - One probe per date: the last standing row, read off the (savings_account_id, transaction_date, id) index.
    // - A date with no standing transaction left loses its snapshot row.
    // - Any other date takes the running balance of that last standing row.
    private static final String DRAIN_SQL = """
            WITH picked AS (
                SELECT savings_account_id, balance_date
                FROM m_savings_daily_balance_dirty
                ORDER BY savings_account_id, balance_date
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            ),
            taken AS (
                DELETE FROM m_savings_daily_balance_dirty d
                USING picked p
                WHERE d.savings_account_id = p.savings_account_id
                  AND d.balance_date       = p.balance_date
                RETURNING d.savings_account_id, d.balance_date
            ),
            latest AS (
                SELECT d.savings_account_id, d.balance_date, t.running_balance_derived AS end_of_day_balance
                FROM taken d
                LEFT JOIN LATERAL (
                    SELECT running_balance_derived
                    FROM m_savings_account_transaction
                    WHERE savings_account_id = d.savings_account_id
                      AND transaction_date   = d.balance_date
                      AND is_reversed = false
                      AND is_reversal = false
                      AND original_transaction_id IS NULL
                      AND running_balance_derived IS NOT NULL
                    ORDER BY id DESC
                    LIMIT 1
                ) t ON TRUE
            ),
            emptied AS (
                DELETE FROM m_savings_account_daily_balance s
                USING latest l
                WHERE s.savings_account_id = l.savings_account_id
                  AND s.balance_date       = l.balance_date
                  AND l.end_of_day_balance IS NULL
                RETURNING 1
            ),
            refreshed AS (
                INSERT INTO m_savings_account_daily_balance (savings_account_id, balance_date, end_of_day_balance)
                SELECT savings_account_id, balance_date, end_of_day_balance
                FROM latest
                WHERE end_of_day_balance IS NOT NULL
                ON CONFLICT (savings_account_id, balance_date)
                DO UPDATE SET end_of_day_balance = EXCLUDED.end_of_day_balance
                WHERE m_savings_account_daily_balance.end_of_day_balance
                      IS DISTINCT FROM EXCLUDED.end_of_day_balance
                RETURNING 1
            )
            SELECT (SELECT COUNT(*) FROM taken) AS drained,
                   (SELECT COUNT(*) FROM emptied) AS emptied,
                   (SELECT COUNT(*) FROM refreshed) AS refreshed
            """;

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
        return jdbcTemplate.update(ENQUEUE_TOUCHED_STANDING_SQL, params) + jdbcTemplate.update(ENQUEUE_TOUCHED_REVERSED_SQL, params);
    }

    @Override
    public void enqueueDirty(Long savingsAccountId, LocalDate balanceDate) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("accountId", savingsAccountId).addValue("balanceDate",
                java.sql.Date.valueOf(balanceDate));
        jdbcTemplate.update(ENQUEUE_DIRTY_SQL, params);
    }

    @Override
    public int drainDirty(final int batchSize) {
        final Integer drained = jdbcTemplate.queryForObject(DRAIN_SQL, new MapSqlParameterSource().addValue("batchSize", batchSize),
                (rs, rowNum) -> rs.getInt("drained"));
        return drained == null ? 0 : drained;
    }
}
