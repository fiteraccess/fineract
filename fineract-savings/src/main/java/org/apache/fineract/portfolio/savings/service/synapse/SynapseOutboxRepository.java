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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Task-type agnostic JDBC repository for the {@code synapse_outbox} table.
 */
@Slf4j
public class SynapseOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private static final String INSERT_SQL = "INSERT INTO synapse_outbox "
            + "(trace_id, batch_id, task_type, account_id, office_id, payload, status, attempts, max_attempts, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)";

    private static final String CLAIM_SQL = "UPDATE synapse_outbox SET status = 'DISPATCHED', "
            + "dispatched_at = ?, attempts = attempts + 1 " + "WHERE id IN (" + "  SELECT id FROM synapse_outbox "
            + "  WHERE status = 'PENDING' AND task_type = ? AND attempts < max_attempts "
            + "  AND (next_attempt_at IS NULL OR next_attempt_at <= ?) " + "  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"
            + ") RETURNING id, trace_id, batch_id, task_type, account_id, office_id, "
            + "payload, status, attempts, max_attempts, error_detail, created_at, dispatched_at, completed_at, next_attempt_at";

    private static final String MARK_SENT_SQL = "UPDATE synapse_outbox SET status = 'SENT', completed_at = ? WHERE id IN (%s)";

    private static final String MARK_FAILED_SQL = "UPDATE synapse_outbox SET status = CASE WHEN attempts >= ? "
            + "THEN 'DEAD' ELSE 'PENDING' END, error_detail = ?, next_attempt_at = ? WHERE id = ?";

    private static final String RESET_TO_PENDING_SQL = "UPDATE synapse_outbox SET status = 'PENDING', dispatched_at = NULL, "
            + "attempts = GREATEST(attempts - 1, 0) WHERE id IN (%s)";

    private static final String RETRY_DEAD_SQL = "UPDATE synapse_outbox SET status = 'PENDING', attempts = 0, "
            + "next_attempt_at = NULL WHERE id = ? AND status = 'DEAD'";

    private static final String RECLAIM_STALE_DISPATCHED_SQL = "UPDATE synapse_outbox SET status = 'PENDING', dispatched_at = NULL "
            + "WHERE status = 'DISPATCHED' AND dispatched_at < ?";

    private static final String PURGE_SQL = "DELETE FROM synapse_outbox WHERE status = 'SENT' AND completed_at < ?";

    private static final String STATS_SQL = "SELECT task_type, status, COUNT(*) as cnt FROM synapse_outbox GROUP BY task_type, status";

    private static final OutboxEntryRowMapper ROW_MAPPER = new OutboxEntryRowMapper();

    public SynapseOutboxRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public SynapseOutboxRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    /**
     * Batch-insert outbox entries within the current transaction.
     */
    public void insertBatch(String taskType, String batchId, List<OutboxEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        jdbcTemplate.batchUpdate(INSERT_SQL, entries, entries.size(), (PreparedStatement ps, OutboxEntry e) -> {
            ps.setString(1, e.getTraceId());
            ps.setString(2, batchId);
            ps.setString(3, taskType);
            ps.setLong(4, e.getAccountId());
            ps.setObject(5, e.getOfficeId());
            ps.setString(6, e.getPayload());
            ps.setInt(7, e.getMaxAttempts());
            ps.setTimestamp(8, Timestamp.from(now));
        });
        log.debug("Inserted {} outbox entries for taskType={} batchId={}", entries.size(), taskType, batchId);
    }

    /**
     * Claim up to {@code limit} PENDING rows for the given task type. Uses {@code FOR UPDATE SKIP LOCKED} to avoid
     * contention. Claimed rows are atomically moved to DISPATCHED status.
     *
     * @return the claimed entries (status = DISPATCHED)
     */
    public List<OutboxEntry> claimPending(String taskType, int limit) {
        Timestamp now = Timestamp.from(clock.instant());
        List<OutboxEntry> entries = jdbcTemplate.query(CLAIM_SQL, ROW_MAPPER, now, taskType, now, limit);
        if (!entries.isEmpty()) {
            log.debug("Claimed {} outbox entries for taskType={}", entries.size(), taskType);
        }
        return entries;
    }

    /**
     * Mark the given rows as SENT with completion timestamp.
     */
    public void markSent(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = String.format(MARK_SENT_SQL, placeholders);
        Object[] params = new Object[ids.size() + 1];
        params[0] = Timestamp.from(clock.instant());
        for (int i = 0; i < ids.size(); i++) {
            params[i + 1] = ids.get(i);
        }
        jdbcTemplate.update(sql, params);
        log.debug("Marked {} outbox entries as SENT", ids.size());
    }

    private static final double BACKOFF_BASE_MINUTES = 1.0;
    private static final double BACKOFF_MULTIPLIER = 1.5;
    private static final double BACKOFF_MAX_MINUTES = 15.0;
    private static final double JITTER_FACTOR = 0.2;
    private static final Duration RETRY_DEADLINE = Duration.ofHours(24);

    /**
     * Mark a single row as FAILED (or DEAD if max attempts or retry deadline reached). Applies exponential backoff with
     * ±20% jitter for the next retry attempt.
     */
    public void markFailed(Long id, String errorDetail, int currentAttempts, int maxAttempts, Instant createdAt) {
        boolean isDead = (currentAttempts + 1) >= maxAttempts || clock.instant().isAfter(createdAt.plus(RETRY_DEADLINE));
        Timestamp nextAttempt = null;

        if (!isDead) {
            double delayMinutes = calculateBackoffMinutes(currentAttempts);
            nextAttempt = Timestamp.from(clock.instant().plusSeconds((long) (delayMinutes * 60)));
        }

        jdbcTemplate.update(MARK_FAILED_SQL, maxAttempts, errorDetail, nextAttempt, id);
        log.debug("Marked outbox entry id={} as FAILED/DEAD (nextAttempt={})", id, nextAttempt);
    }

    /**
     * Calculate the backoff delay in minutes for the given attempt number. Uses exponential backoff with ±20% jitter,
     * capped at {@link #BACKOFF_MAX_MINUTES}.
     */
    double calculateBackoffMinutes(int currentAttempts) {
        double delayMinutes = BACKOFF_BASE_MINUTES * Math.pow(BACKOFF_MULTIPLIER, currentAttempts);
        delayMinutes = Math.min(delayMinutes, BACKOFF_MAX_MINUTES);
        double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-JITTER_FACTOR, JITTER_FACTOR);
        return delayMinutes * jitter;
    }

    /**
     * Returns outbox entry counts grouped by task type and status.
     *
     * @return a map of task_type → (status → count)
     */
    public Map<String, Map<String, Long>> getOutboxStats() {
        return jdbcTemplate.query(STATS_SQL, rs -> {
            Map<String, Map<String, Long>> stats = new HashMap<>();
            while (rs.next()) {
                String taskType = rs.getString("task_type");
                String status = rs.getString("status");
                long count = rs.getLong("cnt");
                stats.computeIfAbsent(taskType, k -> new HashMap<>()).put(status, count);
            }
            return stats;
        });
    }

    /**
     * Reset rows back to PENDING without penalising the attempt count. Used when the circuit breaker is open — the
     * entries themselves did not fail.
     */
    public void resetToPending(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = String.format(RESET_TO_PENDING_SQL, placeholders);
        Object[] params = ids.toArray();
        jdbcTemplate.update(sql, params);
        log.debug("Reset {} outbox entries to PENDING (circuit breaker open)", ids.size());
    }

    /**
     * Reset a DEAD outbox entry back to PENDING for manual retry.
     *
     * @param id
     *            the outbox entry id
     * @return the number of rows updated (1 if reset, 0 if not found or not DEAD)
     */
    public int retryDeadEntry(Long id) {
        int updated = jdbcTemplate.update(RETRY_DEAD_SQL, id);
        if (updated > 0) {
            log.debug("Reset DEAD outbox entry id={} to PENDING for retry", id);
        } else {
            log.warn("No DEAD outbox entry found with id={}", id);
        }
        return updated;
    }

    /**
     * Reclaim rows stuck in DISPATCHED status for longer than the given threshold. This handles entries left behind by
     * crashed or interrupted runs. Safe because Synapse enforces trace_id idempotency.
     *
     * @param staleMinutes
     *            entries dispatched more than this many minutes ago are reclaimed
     * @return the number of reclaimed rows
     */
    public int reclaimStaleDispatched(int staleMinutes) {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(staleMinutes, ChronoUnit.MINUTES));
        int reclaimed = jdbcTemplate.update(RECLAIM_STALE_DISPATCHED_SQL, cutoff);
        if (reclaimed > 0) {
            log.info("Reclaimed {} stale DISPATCHED outbox entries older than {} minutes", reclaimed, staleMinutes);
        }
        return reclaimed;
    }

    /**
     * Delete SENT outbox entries older than the given retention period.
     *
     * @param retentionDays
     *            number of days to retain completed entries
     * @return the number of deleted rows
     */
    public int purgeOldSentEntries(int retentionDays) {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retentionDays, ChronoUnit.DAYS));
        int deleted = jdbcTemplate.update(PURGE_SQL, cutoff);
        log.info("Purged {} SENT outbox entries older than {} days (cutoff={})", deleted, retentionDays, cutoff);
        return deleted;
    }

    private static final class OutboxEntryRowMapper implements RowMapper<OutboxEntry> {

        @Override
        public OutboxEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return OutboxEntry.builder().id(rs.getLong("id")).traceId(rs.getString("trace_id")).batchId(rs.getString("batch_id"))
                    .taskType(rs.getString("task_type")).accountId(rs.getLong("account_id")).officeId(readNullableLong(rs, "office_id"))
                    .payload(rs.getString("payload")).status(rs.getString("status")).attempts(rs.getInt("attempts"))
                    .maxAttempts(rs.getInt("max_attempts")).errorDetail(rs.getString("error_detail"))
                    .createdAt(toInstant(rs.getTimestamp("created_at"))).dispatchedAt(toInstant(rs.getTimestamp("dispatched_at")))
                    .completedAt(toInstant(rs.getTimestamp("completed_at"))).nextAttemptAt(toInstant(rs.getTimestamp("next_attempt_at")))
                    .build();
        }

        private static Instant toInstant(Timestamp ts) {
            return ts != null ? ts.toInstant() : null;
        }

        private static Long readNullableLong(ResultSet rs, String column) throws SQLException {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        }
    }
}
