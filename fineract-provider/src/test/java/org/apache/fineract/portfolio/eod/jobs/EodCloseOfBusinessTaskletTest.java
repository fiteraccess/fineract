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
package org.apache.fineract.portfolio.eod.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.fineract.cob.COBBusinessStepService;
import org.apache.fineract.cob.data.BusinessStepNameAndOrder;
import org.apache.fineract.cob.domain.BatchBusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.cob.eod.EodBusinessStep;
import org.apache.fineract.cob.eod.EodRolloverStep;
import org.apache.fineract.cob.eod.EodStepPlan;
import org.apache.fineract.cob.exceptions.BusinessStepException;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.domain.ScheduledJobDetail;
import org.apache.fineract.infrastructure.jobs.domain.ScheduledJobDetailRepository;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;

class EodCloseOfBusinessTaskletTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 18, 0);

    private ConfigurationDomainService configuration;
    private EodRunService runService;
    private COBBusinessStepService steps;
    private BatchBusinessStepRepository stepConfig;
    private ScheduledJobDetailRepository jobs;
    private EodCloseOfBusinessTasklet underTest;
    private StepContribution contribution;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, TODAY, BusinessDateType.COB_DATE, TODAY.minusDays(1))));
        configuration = mock(ConfigurationDomainService.class);
        runService = mock(EodRunService.class);
        steps = mock(COBBusinessStepService.class);
        stepConfig = mock(BatchBusinessStepRepository.class);
        jobs = mock(ScheduledJobDetailRepository.class);
        underTest = new EodCloseOfBusinessTasklet(configuration, runService, steps, stepConfig, jobs);
        when(stepConfig.findAllByJobName(EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn(seededConfig());
        contribution = new StepContribution(mock(StepExecution.class));
        when(configuration.isBusinessDateEnabled()).thenReturn(true);
        when(runService.findOpenRun()).thenReturn(Optional.empty());
        when(runService.findRun(any())).thenReturn(Optional.empty());
        when(runService.findOrStart(any())).thenAnswer(invocation -> EodRun.start(invocation.getArgument(0), NOW));
        when(steps.getCOBBusinessSteps(EodBusinessStep.class, EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn(
                Set.of(new BusinessStepNameAndOrder("eodCompletionStep", 9L), new BusinessStepNameAndOrder("eodRolloverStep", 1L)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    /** One step per call, in order, so each step's business events are published when it finishes. */
    @Test
    void opensARunForFineractsBusinessDate_andRunsTheConfiguredStepsOneByOneInOrder() {
        underTest.execute(contribution, mock(ChunkContext.class));

        verify(runService).findOrStart(TODAY);
        ArgumentCaptor<TreeMap<Long, String>> calls = ArgumentCaptor.forClass(TreeMap.class);
        verify(steps, times(2)).run(calls.capture(), any(EodRun.class));
        assertEquals(List.of(new TreeMap<>(Map.of(1L, "eodRolloverStep")), new TreeMap<>(Map.of(9L, "eodCompletionStep"))),
                calls.getAllValues());
        verify(runService).completeRun(any(EodRun.class));
    }

    /** After the 18:00 close Fineract's business date is tomorrow; a second trigger that evening must not close it. */
    @Test
    void aBusinessDateTheCalendarHasNotReachedIsANoOp() {
        LocalDate tomorrow = DateUtils.getLocalDateOfTenant().plusDays(1);
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, tomorrow, BusinessDateType.COB_DATE, tomorrow.minusDays(1))));

        underTest.execute(contribution, mock(ChunkContext.class));

        assertEquals(ExitStatus.NOOP, contribution.getExitStatus());
        verify(runService, never()).findOrStart(any());
        verify(steps, never()).run(any(), any());
    }

    /** An open run is resumed whatever its date: the calendar guard only stops a new day from being opened early. */
    @Test
    void anOpenRunIsResumedEvenWhenItsDateIsAheadOfTheCalendar() {
        LocalDate tomorrow = DateUtils.getLocalDateOfTenant().plusDays(1);
        EodRun open = EodRun.start(tomorrow, NOW);
        when(runService.findOpenRun()).thenReturn(Optional.of(open));

        underTest.execute(contribution, mock(ChunkContext.class));

        verify(runService).findOrStart(tomorrow);
        verify(runService).completeRun(any(EodRun.class));
    }

    @Test
    void resumesTheEarliestOpenRunBeforeOpeningANewDay() {
        when(runService.findOpenRun()).thenReturn(Optional.of(EodRun.start(TODAY.minusDays(1), NOW.minusDays(1))));

        underTest.execute(contribution, mock(ChunkContext.class));

        verify(runService).findOrStart(TODAY.minusDays(1));
    }

    /**
     * A resumed run skips the completed rollover, so the dates come from the database; with COB-date adjustment off
     * that COB_DATE is stale. The tasklet sets the day to close before any later step runs.
     */
    @Test
    void aResumedRunWithTheRolloverDoneClosesItsOwnDay() {
        LocalDate closing = TODAY.minusDays(1);
        EodRun open = EodRun.start(closing, NOW.minusDays(1));
        when(runService.findOpenRun()).thenReturn(Optional.of(open));
        when(runService.findOrStart(closing)).thenReturn(open);
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, TODAY, BusinessDateType.COB_DATE, closing.minusDays(5))));
        when(runService.findStep(open, EodRolloverStep.NAME)).thenReturn(Optional.of(completedRollover(open)));
        Map<BusinessDateType, LocalDate> seenByTheSteps = new HashMap<>();
        doAnswer(invocation -> {
            seenByTheSteps.put(BusinessDateType.BUSINESS_DATE,
                    ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
            seenByTheSteps.put(BusinessDateType.COB_DATE, ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE));
            return null;
        }).when(steps).run(any(), eq(open));

        underTest.execute(contribution, mock(ChunkContext.class));

        assertEquals(Map.of(BusinessDateType.BUSINESS_DATE, TODAY, BusinessDateType.COB_DATE, closing), seenByTheSteps);
    }

    @Test
    void aRunWhoseRolloverHasNotRunLeavesTheDatesToTheRollover() {
        when(runService.findStep(any(EodRun.class), eq(EodRolloverStep.NAME))).thenReturn(Optional.empty());

        underTest.execute(contribution, mock(ChunkContext.class));

        assertEquals(TODAY.minusDays(1), ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE));
    }

    @Test
    void aDayAlreadyClosedIsANoOp() {
        EodRun done = EodRun.start(TODAY, NOW);
        done.setStatus(EodRun.STATUS_COMPLETED);
        when(runService.findRun(TODAY)).thenReturn(Optional.of(done));

        underTest.execute(contribution, mock(ChunkContext.class));

        assertEquals(ExitStatus.NOOP, contribution.getExitStatus());
        verify(steps, never()).run(any(), any());
    }

    @Test
    void aFailingStepFailsTheRunAndTheJob() {
        when(steps.run(any(), any(EodRun.class))).thenThrow(new BusinessStepException("step failed", new RuntimeException("boom")));

        assertThrows(BusinessStepException.class, () -> underTest.execute(contribution, mock(ChunkContext.class)));

        verify(runService).failRun(any(EodRun.class), eq("boom"));
        verify(runService, never()).completeRun(any());
    }

    @Test
    void withoutConfiguredStepsTheJobFails_andTheRunIsMarkedFailed() {
        when(steps.getCOBBusinessSteps(EodBusinessStep.class, EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn(Set.of());

        assertEquals(EodCloseOfBusinessTasklet.CODE_NO_STEPS,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(contribution, mock(ChunkContext.class))).getCode());
        verify(runService).failRun(any(EodRun.class), eq("no business steps are configured for " + EodCloseOfBusinessTasklet.JOB_NAME));
        verify(runService, never()).completeRun(any());
    }

    @Test
    void withoutTheBusinessDateFeatureTheJobFails() {
        when(configuration.isBusinessDateEnabled()).thenReturn(false);

        assertEquals(EodCloseOfBusinessTasklet.CODE_PREFLIGHT,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(contribution, mock(ChunkContext.class))).getCode());
        verify(runService, never()).findOrStart(any());
        verify(runService, never()).findRun(any());
        verifyNoStepsRan();
    }

    /**
     * Two steps sharing an order would collapse into one entry of the execution map; the stored rows are checked first.
     */
    @Test
    void anInvalidStoredConfigurationFailsTheRunBeforeAnyStep() {
        List<BatchBusinessStep> config = seededConfig();
        config.add(row("EOD_MIRROR", 5L));
        when(stepConfig.findAllByJobName(EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn(config);

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class,
                () -> underTest.execute(contribution, mock(ChunkContext.class)));

        assertEquals(EodCloseOfBusinessTasklet.CODE_STEPS_INVALID, thrown.getCode());
        verify(runService).failRun(any(EodRun.class),
                eq("invalid EOD step configuration: order 5 is used by [EOD_BILLS_GATE, EOD_MIRROR]"));
        verify(steps, never()).run(any(), any());
        verify(runService, never()).completeRun(any());
    }

    @Test
    void anActiveJobTheChainReplacesBlocksTheRunBeforeOneIsOpened() {
        ScheduledJobDetail activeDateJob = job(true);
        ScheduledJobDetail inactiveInterestJob = job(false);
        when(jobs.findByJobName(JobName.INCREASE_BUSINESS_DATE_BY_1_DAY.toString())).thenReturn(activeDateJob);
        when(jobs.findByJobName(JobName.POST_INTEREST_FOR_SAVINGS.toString())).thenReturn(inactiveInterestJob);

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class,
                () -> underTest.execute(contribution, mock(ChunkContext.class)));

        assertEquals(EodCloseOfBusinessTasklet.CODE_PREFLIGHT_CONFLICT, thrown.getCode());
        assertEquals("deactivate the jobs the EOD chain replaces before it runs; still active: [Increase Business Date by 1 day]",
                thrown.getMessage());
        verify(runService, never()).findOrStart(any());
        verifyNoStepsRan();
    }

    private static List<BatchBusinessStep> seededConfig() {
        List<BatchBusinessStep> config = new ArrayList<>();
        long order = 1;
        for (String name : EodStepPlan.REQUIRED) {
            config.add(row(name, name.equals("EOD_COMPLETION") ? 9L : order++));
        }
        return config;
    }

    private static BatchBusinessStep row(String stepName, Long order) {
        BatchBusinessStep row = new BatchBusinessStep();
        row.setJobName(EodCloseOfBusinessTasklet.JOB_NAME);
        row.setStepName(stepName);
        row.setStepOrder(order);
        return row;
    }

    private static ScheduledJobDetail job(boolean active) {
        ScheduledJobDetail job = mock(ScheduledJobDetail.class);
        when(job.isActiveSchedular()).thenReturn(active);
        return job;
    }

    private void verifyNoStepsRan() {
        verify(steps, never()).run(any(), any());
        verify(runService, never()).failRun(any(), anyString());
    }

    private static EodStep completedRollover(EodRun run) {
        EodStep step = EodStep.start(run, EodRolloverStep.NAME, NOW.minusDays(1));
        step.setStatus(EodStep.STATUS_COMPLETED);
        step.setCompletedAt(NOW.minusDays(1));
        return step;
    }
}
