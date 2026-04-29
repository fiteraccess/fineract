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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.domain.SavingsDailyBalanceSyncRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrator for the savings daily-balance sync subsystem (§4.2 of the optimization plan).
 *
 * <p>
 * {@link #syncNow()} is the single public entry point. It:
 * <ol>
 * <li>Acquires a {@code FOR UPDATE} lock on the singleton watermark row (serializes concurrent runs),</li>
 * <li>Runs Pass 1 in fixed-size hourly windows up to "now" (bounds WAL bursts on backfill / large gaps),</li>
 * <li>Runs Pass 2 (drain dirty),</li>
 * <li>Advances the watermark and commits.</li>
 * </ol>
 *
 * <p>
 * Idempotent and safe to invoke from the hourly job, the interest tasklet, and tests.
 *
 * <p>
 * <b>Race note:</b> {@code upTo} is set to {@code now()} with no pullback. This is correct when the only contention is
 * with other batch runs (serialized by the {@code FOR UPDATE} lock). A theoretical race exists where a writer
 * transaction sets {@code last_modified_on_utc < upTo} but commits after our scan — that row would be missed forever.
 * In practice this is rare (Postgres commits in milliseconds) and self-healing for reversals via the dirty
 * side-channel. For non-reversal cascades (backdated deposits/withdrawals that rewrite running_balance_derived on many
 * subsequent transactions), all rewrites land in the same writer transaction; either we see all of them or none, and
 * the next batch run's watermark scan catches the missed ones.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SavingsDailyBalanceSyncService {

    private static final int MAX_WINDOW_HOURS = 1;

    private final SavingsDailyBalanceSyncRepository repo;

    /**
     * Bounded result of a single {@link #syncNow()} invocation. Used by the tasklet for log lines / metrics.
     */
    public record SyncResult(int upserted, int drained, OffsetDateTime from, OffsetDateTime to) {
    }

    /**
     * Pullback applied to {@code upTo} to guard against the JPA-auditing race: a writer transaction sets
     * {@code last_modified_on_utc} at flush time but the row only becomes visible at commit time. Without a pullback, a
     * row whose {@code last_modified_on_utc} is set just before our scan but committed just after would be missed
     * forever. 100ms is short enough that backdate/reversal cascades — which take at least one HTTP round-trip plus a
     * scheduler poll cycle to land before the next SA_DSYNC — are always picked up; long enough to cover normal
     * Postgres commit latency.
     */
    private static final int UP_TO_PULLBACK_MILLIS = 100;

    @Transactional
    public SyncResult syncNow() {
        OffsetDateTime watermark = repo.readAndLockWatermark();
        OffsetDateTime upTo = OffsetDateTime.now(ZoneOffset.UTC).minusNanos(UP_TO_PULLBACK_MILLIS * 1_000_000L);

        if (!watermark.isBefore(upTo)) {
            // Edge case: clock skew across replicas pushed watermark past now(). Drain dirty anyway in case a reversal
            // landed since the previous run, but skip Pass 1 and the watermark advance (would be non-monotonic).
            int drainedOnly = repo.drainDirty();
            return new SyncResult(0, drainedOnly, watermark, upTo);
        }

        int upserted = 0;
        OffsetDateTime cursor = watermark;
        while (cursor.plusHours(MAX_WINDOW_HOURS).isBefore(upTo)) {
            OffsetDateTime windowEnd = cursor.plusHours(MAX_WINDOW_HOURS);
            upserted += repo.syncFromTransactions(cursor, windowEnd);
            cursor = windowEnd;
        }
        upserted += repo.syncFromTransactions(cursor, upTo);

        int drained = repo.drainDirty();
        repo.advanceWatermark(upTo);
        return new SyncResult(upserted, drained, watermark, upTo);
    }
}
