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
package org.apache.fineract.portfolio.eod.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodExceptionRepository;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodRunRepository;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.domain.EodStepRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EodRunServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime AT = D.atTime(9, 0);

    private EodRunRepository runs;
    private EodStepRepository steps;
    private EodExceptionRepository exceptions;
    private EodRunService underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        runs = mock(EodRunRepository.class);
        steps = mock(EodStepRepository.class);
        exceptions = mock(EodExceptionRepository.class);
        underTest = new EodRunService(runs, steps, exceptions);
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void aNewDayStartsARunningRun() {
        when(runs.findByBusinessDate(D)).thenReturn(Optional.empty());

        EodRun run = underTest.findOrStart(D);

        assertEquals(EodRun.STATUS_RUNNING, run.getStatus());
        assertEquals(1, run.getAttempts());
        assertEquals(D, run.getBusinessDate());
    }

    @Test
    void anOpenRunIsReAttempted_aCompletedOneIsReturnedAsIs() {
        EodRun failed = EodRun.start(D, AT);
        failed.setStatus(EodRun.STATUS_FAILED);
        failed.setLastError("boom");
        when(runs.findByBusinessDate(D)).thenReturn(Optional.of(failed));

        EodRun resumed = underTest.findOrStart(D);

        assertEquals(EodRun.STATUS_RUNNING, resumed.getStatus());
        assertEquals(2, resumed.getAttempts());
        assertNull(resumed.getLastError());

        EodRun done = EodRun.start(D, AT);
        done.setStatus(EodRun.STATUS_COMPLETED);
        when(runs.findByBusinessDate(D)).thenReturn(Optional.of(done));
        assertSame(done, underTest.findOrStart(D));
        verify(runs, never()).saveAndFlush(done);
    }

    @Test
    void aCompletedStepIsHandedBackUntouched_anyOtherIsRestarted() {
        EodRun run = EodRun.start(D, AT);
        EodStep done = EodStep.start(run, "EOD_ROLLOVER", AT);
        done.setStatus(EodStep.STATUS_COMPLETED);
        when(steps.findByRunAndStepName(run, "EOD_ROLLOVER")).thenReturn(Optional.of(done));
        EodStep failed = EodStep.start(run, "EOD_ACCRUAL", AT);
        failed.setStatus(EodStep.STATUS_FAILED);
        failed.setErrorCode("X");
        when(steps.findByRunAndStepName(run, "EOD_ACCRUAL")).thenReturn(Optional.of(failed));
        when(steps.findByRunAndStepName(run, "EOD_NEW")).thenReturn(Optional.empty());

        assertTrue(underTest.beginStep(run, "EOD_ROLLOVER").isCompleted());
        EodStep restarted = underTest.beginStep(run, "EOD_ACCRUAL");
        assertEquals(EodStep.STATUS_RUNNING, restarted.getStatus());
        assertEquals(2, restarted.getAttempts());
        assertNull(restarted.getErrorCode());
        assertEquals(1, underTest.beginStep(run, "EOD_NEW").getAttempts());
    }

    /** A retried or resumed step reports the same exception again; the report counts it once. */
    @Test
    void anExceptionAlreadyRecordedForTheRunStepCodeAndReferenceIsNotInsertedAgain() {
        EodRun run = EodRun.start(D, AT);
        when(exceptions.existsByRunAndStepNameAndCodeAndReference(run, "EOD_REPLAY_DRAIN", "EOD-REPLAY-LEGACY", "ref-1")).thenReturn(false,
                true);

        underTest.recordException(run, "EOD_REPLAY_DRAIN", EodException.SEVERITY_WARN, "EOD-REPLAY-LEGACY", "old failure", "ref-1");
        underTest.recordException(run, "EOD_REPLAY_DRAIN", EodException.SEVERITY_WARN, "EOD-REPLAY-LEGACY", "old failure", "ref-1");

        verify(exceptions, times(1)).saveAndFlush(any(EodException.class));
    }

    @Test
    void aNullReferenceMatchesANullReference() {
        EodRun run = EodRun.start(D, AT);
        when(exceptions.existsByRunAndStepNameAndCodeAndReferenceIsNull(run, "EOD_BILLS_GATE", "EOD-BILLS-NO-FILE")).thenReturn(true);

        underTest.recordException(run, "EOD_BILLS_GATE", EodException.SEVERITY_WARN, "EOD-BILLS-NO-FILE", "no file", null);

        verify(exceptions, never()).saveAndFlush(any(EodException.class));
    }

    @Test
    void checkpointDetailIsStoredAsJson() {
        EodRun run = EodRun.start(D, AT);
        EodStep step = EodStep.start(run, "EOD_ROLLOVER", AT);

        underTest.completed(step, Map.of("from", "2026-10-06"));

        assertEquals(EodStep.STATUS_COMPLETED, step.getStatus());
        assertEquals("{\"from\":\"2026-10-06\"}", step.getDetail());
    }
}
