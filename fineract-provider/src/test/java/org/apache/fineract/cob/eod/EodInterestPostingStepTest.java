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
package org.apache.fineract.cob.eod;

import static org.apache.fineract.cob.eod.EodStepTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseReplayStatus;
import org.apache.fineract.portfolio.savings.jobs.postinterestforsavings.PostInterestForSavingsService;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class EodInterestPostingStepTest {

    /** Mid-month on purpose: posting runs every night and each product's posting period decides what is due. */
    private static final LocalDate D = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime ROLLED_AT = LocalDateTime.of(2026, 10, 14, 18, 0);
    /** 18:00 in Africa/Lagos (UTC+1). */
    private static final Instant ROLLED_AT_INSTANT = Instant.parse("2026-10-14T17:00:00Z");

    private EodRunService runService;
    private PostInterestForSavingsService posting;
    private SynapseOutboxRepository outbox;
    private SynapseTransactionClient synapse;
    private EodInterestPostingStep underTest;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        posting = mock(PostInterestForSavingsService.class);
        outbox = mock(SynapseOutboxRepository.class);
        synapse = mock(SynapseTransactionClient.class);
        ObjectProvider<SynapseOutboxRepository> outboxProvider = mock(ObjectProvider.class);
        when(outboxProvider.getIfAvailable()).thenReturn(outbox);
        ObjectProvider<SynapseTransactionClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodInterestPostingStep(runService, EodStepTestSupport.fastProperties(), posting, outboxProvider, clientProvider);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nothingDueCompletesAfterPostingWithoutAskingSynapse() throws Exception {
        EodRun run = rolledAndAccrued(D);
        when(outbox.countByStatusSince("INTEREST_POSTING", ROLLED_AT_INSTANT)).thenReturn(Map.of());

        underTest.execute(run);

        verify(posting).postInterest(10, 100);
        verify(synapse, never()).getReplayStatus(any());
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        assertEquals(0, detail.getValue().get("posted"));
    }

    @Test
    void entriesWrittenWaitUntilSentAndLandedInFineract() throws Exception {
        EodRun run = rolledAndAccrued(D);
        when(outbox.countByStatusSince("INTEREST_POSTING", ROLLED_AT_INSTANT)).thenReturn(Map.of("PENDING", 7L),
                Map.of("PENDING", 2L, "SENT", 5L), Map.of("SENT", 7L));
        when(synapse.getReplayStatus(D)).thenReturn(replay(Map.of("POSTED_TO_TB", 3L, "POSTED_TO_FINERACT", 4L)),
                replay(Map.of("POSTED_TO_FINERACT", 7L)));

        underTest.execute(run);

        verify(posting).postInterest(10, 100);
        verify(synapse, times(2)).getReplayStatus(D);
        verify(runService).waiting(any(), any());
        verify(runService).completed(any(), any());
    }

    @Test
    void aRetryStillWaitsOnTheFirstAttemptsPendingEntries() throws Exception {
        EodRun run = rolledAndAccrued(D);
        when(runService.beginStep(any(EodRun.class), eq(EodInterestPostingStep.NAME))).thenAnswer(invocation -> {
            EodStep retried = EodStep.start(run, EodInterestPostingStep.NAME, ROLLED_AT.plusHours(1));
            retried.setAttempts(2);
            return retried;
        });
        when(outbox.countByStatusSince("INTEREST_POSTING", ROLLED_AT_INSTANT)).thenReturn(Map.of("PENDING", 4L), Map.of("PENDING", 4L),
                Map.of("SENT", 4L));
        when(synapse.getReplayStatus(D)).thenReturn(replay(Map.of("POSTED_TO_FINERACT", 4L)));

        underTest.execute(run);

        verify(outbox, times(3)).countByStatusSince("INTEREST_POSTING", ROLLED_AT_INSTANT);
        verify(runService).waiting(any(), any());
    }

    /** A DEAD interest entry holds its account's later entries, so an old one would leave this run hanging. */
    @Test
    void anOldDeadEntryStopsTheStepBeforeItPosts_namingTheAccounts() throws Exception {
        EodRun run = rolledAndAccrued(D);
        when(outbox.countDead("INTEREST_POSTING")).thenReturn(2L);
        when(outbox.findDeadAccountIds("INTEREST_POSTING", EodInterestPostingStep.DEAD_ACCOUNTS_SHOWN)).thenReturn(List.of(41L, 42L));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodInterestPostingStep.CODE_POSTING_BLOCKED, thrown.getCode());
        assertTrue(thrown.getMessage().contains("[41, 42]"));
        assertTrue(thrown.getMessage().contains("outbox admin"));
        verify(posting, never()).postInterest(anyInt(), anyInt());
    }

    @Test
    void anEntryGoingDeadWhileWaitingFailsTheStep() throws Exception {
        EodRun run = rolledAndAccrued(D);
        when(outbox.countDead("INTEREST_POSTING")).thenReturn(0L, 1L);
        when(outbox.countByStatusSince("INTEREST_POSTING", ROLLED_AT_INSTANT)).thenReturn(Map.of("DEAD", 1L, "SENT", 6L));

        assertEquals(EodInterestPostingStep.CODE_POSTING_DEAD,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    void anInterestRowThatFailedToReachFineractFailsTheStep() throws Exception {
        EodRun run = rolledAndAccrued(D);
        when(outbox.countByStatusSince("INTEREST_POSTING", ROLLED_AT_INSTANT)).thenReturn(Map.of("SENT", 7L));
        when(synapse.getReplayStatus(D)).thenReturn(replay(Map.of("POSTED_TO_FINERACT", 6L, "FAILED_POST_TO_FINERACT", 1L)));

        assertEquals(EodInterestPostingStep.CODE_POSTING_REPLAY_FAILED,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    void requiresTheAccrual() throws Exception {
        EodRun run = EodRun.start(D, NOW);
        EodStepTestSupport.freshCheckpoints(runService, run);

        assertEquals(AbstractEodStep.CODE_PREREQUISITE, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
        verify(posting, never()).postInterest(anyInt(), anyInt());
    }

    private EodRun rolledAndAccrued(LocalDate businessDate) {
        EodRun run = EodRun.start(businessDate, NOW);
        EodStepTestSupport.freshCheckpoints(runService, run);
        when(runService.findStep(run, EodRolloverStep.NAME))
                .thenReturn(Optional.of(EodStepTestSupport.completedStep(run, EodRolloverStep.NAME, ROLLED_AT)));
        when(runService.findStep(run, EodAccrualStep.NAME))
                .thenReturn(Optional.of(EodStepTestSupport.completedStep(run, EodAccrualStep.NAME, ROLLED_AT.plusMinutes(5))));
        return run;
    }

    private static SynapseReplayStatus replay(Map<String, Long> interestPostingByStatus) {
        SynapseReplayStatus status = new SynapseReplayStatus();
        status.setInterestRows(Map.of("INTEREST_POSTING", interestPostingByStatus));
        status.setDrained(true);
        return status;
    }
}
