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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.fineract.portfolio.savings.domain.SavingsDailyBalanceSyncRepository;
import org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService.SyncResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SavingsDailyBalanceSyncService} covering the §7.1 verification cases. The repository is mocked
 * — these tests assert orchestration (call ordering, windowing, watermark advancement, drain), not SQL behavior.
 */
@ExtendWith(MockitoExtension.class)
class SavingsDailyBalanceSyncServiceTest {

    @Mock
    private SavingsDailyBalanceSyncRepository repo;

    @InjectMocks
    private SavingsDailyBalanceSyncService subject;

    @Test
    void syncNowEmptySource_advancesWatermarkAndDrainsOnce() {
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(0);
        when(repo.drainDirty()).thenReturn(0);

        SyncResult result = subject.syncNow();

        InOrder order = inOrder(repo);
        order.verify(repo).readAndLockWatermark();
        order.verify(repo).syncFromTransactions(any(), any());
        order.verify(repo).drainDirty();
        order.verify(repo).advanceWatermark(any());

        assertThat(result.upserted()).isEqualTo(0);
        assertThat(result.drained()).isEqualTo(0);
        assertThat(result.from()).isEqualTo(watermark);
    }

    @Test
    void syncNowSinglePass_returnsCountFromRepo() {
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(15);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(7);
        when(repo.drainDirty()).thenReturn(2);

        SyncResult result = subject.syncNow();

        // Watermark within 1h of now → exactly one tail call, no full-hour windows.
        verify(repo, times(1)).syncFromTransactions(any(), any());
        verify(repo).drainDirty();
        verify(repo).advanceWatermark(any());

        assertThat(result.upserted()).isEqualTo(7);
        assertThat(result.drained()).isEqualTo(2);
    }

    @Test
    void syncNowLargeGap_iteratesInOneHourWindowsThenTail() {
        // Watermark 3.5h behind → expect 3 full windows + 1 tail = 4 calls.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime watermark = now.minusHours(3).minusMinutes(30);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(1);
        when(repo.drainDirty()).thenReturn(0);

        SyncResult result = subject.syncNow();

        verify(repo, times(4)).syncFromTransactions(any(), any());
        verify(repo).drainDirty();
        verify(repo).advanceWatermark(any());

        // Sum of all calls: 1 per call * 4 = 4 upserts.
        assertThat(result.upserted()).isEqualTo(4);

        // Verify cursor advances monotonically through windows.
        ArgumentCaptor<OffsetDateTime> fromCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo, times(4)).syncFromTransactions(fromCaptor.capture(), toCaptor.capture());
        for (int i = 1; i < fromCaptor.getAllValues().size(); i++) {
            // Each window's start should equal the previous window's end.
            assertThat(fromCaptor.getAllValues().get(i)).isEqualTo(toCaptor.getAllValues().get(i - 1));
        }
    }

    @Test
    void syncNowOrdering_lockBeforeSyncBeforeDrainBeforeAdvance() {
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(3);
        when(repo.drainDirty()).thenReturn(1);

        subject.syncNow();

        InOrder order = inOrder(repo);
        order.verify(repo).readAndLockWatermark();
        order.verify(repo).syncFromTransactions(any(), any());
        order.verify(repo).drainDirty();
        order.verify(repo).advanceWatermark(any());
    }

    @Test
    void syncNowAdvancesWatermarkToUpToNotToWatermark() {
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(0);
        when(repo.drainDirty()).thenReturn(0);

        SyncResult result = subject.syncNow();

        ArgumentCaptor<OffsetDateTime> advanceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).advanceWatermark(advanceCaptor.capture());

        // The advanced value is upTo (≈ now - 1s), strictly after the original watermark.
        assertThat(advanceCaptor.getValue()).isAfter(watermark);
        // SyncResult.to() carries the same upTo value used to advance the watermark.
        assertThat(result.to()).isEqualTo(advanceCaptor.getValue());
    }

    @Test
    void syncNowIdempotent_runningTwiceWithFreshWatermarkIsNoop() {
        // Pretend a previous run advanced the watermark to right now → second run should compute upTo ≈ now-1s,
        // which is at-or-before watermark, so it should drain only and not advance.
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.drainDirty()).thenReturn(0);

        SyncResult result = subject.syncNow();

        verify(repo, never()).syncFromTransactions(any(), any());
        verify(repo, never()).advanceWatermark(any());
        verify(repo).drainDirty();
        assertThat(result.upserted()).isEqualTo(0);
    }

    @Test
    void syncNowExactlyOneHourBoundary_takesOneTailCallNoFullWindows() {
        // Watermark exactly 1h behind. cursor.plusHours(1).isBefore(upTo) is false (1h ago + 1h = now, but upTo is
        // now-1s). So no full-window iteration; only the tail call should fire.
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(5);
        when(repo.drainDirty()).thenReturn(0);

        SyncResult result = subject.syncNow();

        verify(repo, times(1)).syncFromTransactions(eq(watermark), any());
        assertThat(result.upserted()).isEqualTo(5);
    }

    @Test
    void syncNowDrainResultPropagatesIntoSyncResult() {
        OffsetDateTime watermark = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        when(repo.readAndLockWatermark()).thenReturn(watermark);
        when(repo.syncFromTransactions(any(), any())).thenReturn(0);
        when(repo.drainDirty()).thenReturn(42);

        SyncResult result = subject.syncNow();

        assertThat(result.drained()).isEqualTo(42);
    }
}
