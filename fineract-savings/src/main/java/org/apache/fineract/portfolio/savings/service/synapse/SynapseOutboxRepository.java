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
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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

    private static final String CLAIM_SQL = "SELECT id, trace_id, batch_id, task_type, account_id, office_id, "
            + "payload, status, attempts, max_attempts, error_detail, created_at, dispatched_at, completed_at "
            + "FROM synapse_outbox WHERE status = 'PENDING' AND task_type = ? AND attempts < max_attempts "
            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";

    private static final String UPDATE_DISPATCHED_SQL = "UPDATE synapse_outbox SET status = 'DISPATCHED', "
            + "dispatched_at = ?, attempts = attempts + 1 WHERE id = ?";

    private static final String MARK_SENT_SQL = "UPDATE synapse_outbox SET status = 'SENT', completed_at = ? WHERE id IN (%s)";

    private static final String MARK_FAILED_SQL = "UPDATE synapse_outbox SET status = CASE WHEN attempts >= max_attempts "
            + "THEN 'DEAD' ELSE 'PENDING' END, error_detail = ? WHERE id = ?";

    private static final String RESET_TO_PENDING_SQL = "UPDATE synapse_outbox SET status = 'PENDING', dispatched_at = NULL, "
            + "attempts = GREATEST(attempts - 1, 0) WHERE id IN (%s)";

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
     * Claim up to {@code limit} PENDING rows for the given task type.
     * Uses {@code FOR UPDATE SKIP LOCKED} to avoid contention.
     * Claimed rows are atomically moved to DISPATCHED status.
     *
     * @return the claimed entries (status = DISPATCHED)
     */
    public List<OutboxEntry> claimPending(String taskType, int limit) {
        List<OutboxEntry> entries = jdbcTemplate.query(CLAIM_SQL, ROW_MAPPER, taskType, limit);
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        Instant now = clock.instant();
        Timestamp dispatchedTs = Timestamp.from(now);
        jdbcTemplate.batchUpdate(UPDATE_DISPATCHED_SQL, entries, entries.size(), (PreparedStatement ps, OutboxEntry entry) -> {
            ps.setTimestamp(1, dispatchedTs);
            ps.setLong(2, entry.getId());
        });
        for (OutboxEntry entry : entries) {
            entry.setStatus("DISPATCHED");
            entry.setDispatchedAt(now);
            entry.setAttempts(entry.getAttempts() + 1);
        }
        log.debug("Claimed {} outbox entries for taskType={}", entries.size(), taskType);
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

    /**
     * Mark a single row as FAILED (or DEAD if max attempts reached).
     */
    public void markFailed(Long id, String errorDetail) {
        jdbcTemplate.update(MARK_FAILED_SQL, errorDetail, id);
        log.debug("Marked outbox entry id={} as FAILED/DEAD", id);
    }

    /**
     * Reset rows back to PENDING without penalising the attempt count.
     * Used when the circuit breaker is open — the entries themselves did not fail.
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

    private static final class OutboxEntryRowMapper implements RowMapper<OutboxEntry> {

        @Override
        public OutboxEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return OutboxEntry.builder()
                    .id(rs.getLong("id"))
                    .traceId(rs.getString("trace_id"))
                    .batchId(rs.getString("batch_id"))
                    .taskType(rs.getString("task_type"))
                    .accountId(rs.getLong("account_id"))
                    .officeId(readNullableLong(rs, "office_id"))
                    .payload(rs.getString("payload"))
                    .status(rs.getString("status"))
                    .attempts(rs.getInt("attempts"))
                    .maxAttempts(rs.getInt("max_attempts"))
                    .errorDetail(rs.getString("error_detail"))
                    .createdAt(toInstant(rs.getTimestamp("created_at")))
                    .dispatchedAt(toInstant(rs.getTimestamp("dispatched_at")))
                    .completedAt(toInstant(rs.getTimestamp("completed_at")))
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
