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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class SynapseOutboxRepositoryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-11T10:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SynapseOutboxRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SynapseOutboxRepository(jdbcTemplate, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void insertBatch_emptyList_skipsJdbc() {
        repository.insertBatch("INTEREST_POSTING", "batch-1", Collections.emptyList());

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void insertBatch_delegatesToBatchUpdate() {
        List<OutboxEntry> entries = List.of(
                OutboxEntry.builder().traceId("t1").accountId(1L).officeId(10L).payload("{}").build(),
                OutboxEntry.builder().traceId("t2").accountId(2L).officeId(20L).payload("{}").build());

        repository.insertBatch("INTEREST_POSTING", "batch-1", entries);

        verify(jdbcTemplate).batchUpdate(
                argThat(sql -> sql.contains("INSERT INTO synapse_outbox")),
                eq(entries), eq(2), any());
    }

    @Test
    void claimPending_noRows_returnsEmptyAndSkipsUpdate() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("INTEREST_POSTING"), eq(Timestamp.from(FIXED_NOW)), eq(10)))
                .thenReturn(Collections.emptyList());

        List<OutboxEntry> result = repository.claimPending("INTEREST_POSTING", 10);

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq("INTEREST_POSTING"), eq(Timestamp.from(FIXED_NOW)), eq(10));
    }

    @Test
    void claimPending_claimsAndMutatesEntries() {
        List<OutboxEntry> entries = List.of(
                OutboxEntry.builder().id(1L).status("PENDING").attempts(0).build(),
                OutboxEntry.builder().id(2L).status("PENDING").attempts(0).build());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("INTEREST_POSTING"), eq(Timestamp.from(FIXED_NOW)), eq(10)))
                .thenReturn(entries);

        List<OutboxEntry> result = repository.claimPending("INTEREST_POSTING", 10);

        verify(jdbcTemplate).batchUpdate(
                argThat(sql -> sql.contains("DISPATCHED")),
                eq(entries), eq(2), any());
        assertThat(result).hasSize(2);
        for (OutboxEntry entry : result) {
            assertThat(entry.getStatus()).isEqualTo("DISPATCHED");
            assertThat(entry.getAttempts()).isEqualTo(1);
            assertThat(entry.getDispatchedAt()).isEqualTo(FIXED_NOW);
        }
    }

    @Test
    void markSent_emptyIds_skipsJdbc() {
        repository.markSent(Collections.emptyList());

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void markSent_buildsDynamicPlaceholders() {
        repository.markSent(List.of(10L, 20L, 30L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("?,?,?");
        Object[] params = paramsCaptor.getValue();
        assertThat(params[0]).isEqualTo(Timestamp.from(FIXED_NOW));
        assertThat(params[1]).isEqualTo(10L);
        assertThat(params[2]).isEqualTo(20L);
        assertThat(params[3]).isEqualTo(30L);
    }

    @Test
    void markFailed_delegatesToUpdate() {
        repository.markFailed(42L, "connection timeout", 2, 20);

        Timestamp expectedNextAttempt = Timestamp.from(FIXED_NOW.plusSeconds((long) (1.0 * Math.pow(1.5, 2) * 60)));
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("CASE WHEN attempts >= ?")),
                eq(20), eq("connection timeout"), eq(expectedNextAttempt), eq(42L));
    }

    @Test
    void markFailed_deadEntry_setsNullNextAttempt() {
        repository.markFailed(42L, "connection timeout", 19, 20);

        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("CASE WHEN attempts >= ?")),
                eq(20), eq("connection timeout"), eq(null), eq(42L));
    }

    @Test
    void resetToPending_buildsDynamicPlaceholders() {
        repository.resetToPending(List.of(10L, 20L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("?,?");
        assertThat(sqlCaptor.getValue()).contains("PENDING");
        Object[] params = paramsCaptor.getValue();
        assertThat(params[0]).isEqualTo(10L);
        assertThat(params[1]).isEqualTo(20L);
    }

    @Test
    void resetToPending_emptyIds_skipsJdbc() {
        repository.resetToPending(Collections.emptyList());

        verifyNoInteractions(jdbcTemplate);
    }

    @Nested
    class MarkFailedBackoff {

        @Test
        void attempt5_producesExpectedBackoffDelay() {
            repository.markFailed(7L, "timeout", 5, 20);

            long expectedDelaySeconds = (long) (1.0 * Math.pow(1.5, 5) * 60);
            Timestamp expectedNextAttempt = Timestamp.from(FIXED_NOW.plusSeconds(expectedDelaySeconds));
            verify(jdbcTemplate).update(
                    argThat(sql -> sql.contains("CASE WHEN attempts >= ?")),
                    eq(20), eq("timeout"), eq(expectedNextAttempt), eq(7L));
        }

        @Test
        void highAttemptCount_capsAtMaxBackoff() {
            repository.markFailed(8L, "timeout", 100, 200);

            Timestamp expectedNextAttempt = Timestamp.from(FIXED_NOW.plusSeconds((long) (1440.0 * 60)));
            verify(jdbcTemplate).update(
                    argThat(sql -> sql.contains("CASE WHEN attempts >= ?")),
                    eq(200), eq("timeout"), eq(expectedNextAttempt), eq(8L));
        }

        @Test
        void firstAttempt_usesBaseDelay() {
            repository.markFailed(9L, "error", 0, 20);

            Timestamp expectedNextAttempt = Timestamp.from(FIXED_NOW.plusSeconds(60));
            verify(jdbcTemplate).update(
                    argThat(sql -> sql.contains("CASE WHEN attempts >= ?")),
                    eq(20), eq("error"), eq(expectedNextAttempt), eq(9L));
        }
    }

    @Nested
    class RetryDeadEntry {

        @Test
        void resetsDeadEntryToPending() {
            when(jdbcTemplate.update(anyString(), eq(99L))).thenReturn(1);

            int updated = repository.retryDeadEntry(99L);

            assertThat(updated).isEqualTo(1);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), eq(99L));
            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("status = 'PENDING'");
            assertThat(sql).contains("attempts = 0");
            assertThat(sql).contains("next_attempt_at = NULL");
            assertThat(sql).contains("status = 'DEAD'");
        }

        @Test
        void returnsZeroWhenEntryNotDead() {
            when(jdbcTemplate.update(anyString(), eq(404L))).thenReturn(0);

            int updated = repository.retryDeadEntry(404L);

            assertThat(updated).isEqualTo(0);
            verify(jdbcTemplate).update(anyString(), eq(404L));
        }
    }

    @Nested
    class GetOutboxStats {

        @SuppressWarnings("unchecked")
        @Test
        void delegatesToJdbcQueryWithResultSetExtractor() {
            when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(Map.of());

            repository.getOutboxStats();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(ResultSetExtractor.class));
            assertThat(sqlCaptor.getValue()).contains("GROUP BY task_type, status");
        }

        @Test
        void returnsStatsGroupedByTaskTypeAndStatus() {
            Map<String, Map<String, Long>> expected = Map.of(
                    "INTEREST_POSTING", Map.of("PENDING", 5L, "SENT", 10L),
                    "BALANCE_SYNC", Map.of("DISPATCHED", 3L));
            when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(expected);

            Map<String, Map<String, Long>> result = repository.getOutboxStats();

            assertThat(result).containsOnlyKeys("INTEREST_POSTING", "BALANCE_SYNC");
            assertThat(result.get("INTEREST_POSTING")).containsEntry("PENDING", 5L).containsEntry("SENT", 10L);
            assertThat(result.get("BALANCE_SYNC")).containsEntry("DISPATCHED", 3L);
        }

        @Test
        void returnsEmptyMapWhenNoRows() {
            when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(Map.of());

            Map<String, Map<String, Long>> result = repository.getOutboxStats();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class PurgeOldSentEntries {

        @Test
        void calculatesCutoffTimestampCorrectly() {
            int retentionDays = 30;
            when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(5);

            repository.purgeOldSentEntries(retentionDays);

            Timestamp expectedCutoff = Timestamp.from(FIXED_NOW.minus(retentionDays, ChronoUnit.DAYS));
            ArgumentCaptor<Timestamp> cutoffCaptor = ArgumentCaptor.forClass(Timestamp.class);
            verify(jdbcTemplate).update(anyString(), cutoffCaptor.capture());
            assertThat(cutoffCaptor.getValue()).isEqualTo(expectedCutoff);
        }

        @Test
        void executesPurgeSqlTargetingSentStatus() {
            when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(0);

            repository.purgeOldSentEntries(7);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Timestamp.class));
            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("DELETE FROM synapse_outbox");
            assertThat(sql).contains("status = 'SENT'");
            assertThat(sql).contains("completed_at < ?");
        }

        @Test
        void returnsDeletedRowCount() {
            when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(42);

            int deleted = repository.purgeOldSentEntries(30);

            assertThat(deleted).isEqualTo(42);
        }

        @Test
        void zeroDaysRetention_cutoffEqualsNow() {
            when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(0);

            repository.purgeOldSentEntries(0);

            ArgumentCaptor<Timestamp> cutoffCaptor = ArgumentCaptor.forClass(Timestamp.class);
            verify(jdbcTemplate).update(anyString(), cutoffCaptor.capture());
            assertThat(cutoffCaptor.getValue()).isEqualTo(Timestamp.from(FIXED_NOW));
        }
    }
}
