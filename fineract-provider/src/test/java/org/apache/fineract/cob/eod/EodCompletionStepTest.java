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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.cob.domain.BatchBusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.jobs.EodCloseOfBusinessTasklet;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EodCompletionStepTest {

    private EodRunService runService;
    private BatchBusinessStepRepository stepConfig;
    private EodCompletionStep underTest;
    private EodRun run;

    @BeforeEach
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        stepConfig = mock(BatchBusinessStepRepository.class);
        when(stepConfig.findAllByJobName(EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn(configured(EodStepPlan.REQUIRED));
        underTest = new EodCompletionStep(runService, EodStepTestSupport.fastProperties(), stepConfig);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void completesWhenEveryRequiredStepDid() {
        when(runService.steps(run)).thenReturn(allRequiredCompleted());

        underTest.execute(run);

        verify(runService).completed(any(), any());
    }

    /** A configuration that dropped a required step must never close the day. */
    @Test
    void aRequiredStepThatNeverRanFailsTheCompletion_namingIt() {
        List<EodStep> withoutBills = allRequiredCompleted().stream().filter(step -> !EodBillsGateStep.NAME.equals(step.getStepName()))
                .toList();
        when(runService.steps(run)).thenReturn(withoutBills);

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodCompletionStep.CODE_INCOMPLETE, thrown.getCode());
        assertTrue(thrown.getMessage().contains(EodBillsGateStep.NAME + " NOT_RUN"));
    }

    @Test
    void anUnfinishedStepFailsTheCompletion_namingIt() {
        EodStep failed = EodStep.start(run, EodBillsGateStep.NAME, NOW);
        failed.setStatus(EodStep.STATUS_FAILED);
        when(runService.steps(run)).thenReturn(List.of(EodStepTestSupport.completedStep(run, EodRolloverStep.NAME, NOW), failed));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodCompletionStep.CODE_INCOMPLETE, thrown.getCode());
        assertTrue(thrown.getMessage().contains(EodBillsGateStep.NAME));
    }

    /** A failed step taken out of the chain is reported, never a reason the day cannot close. */
    @Test
    void aFailedStepNoLongerConfiguredIsAWarning_notABlocker() {
        List<EodStep> steps = new ArrayList<>(allRequiredCompleted());
        EodStep removed = EodStep.start(run, "EOD_MIRROR", NOW);
        removed.setStatus(EodStep.STATUS_FAILED);
        steps.add(removed);
        when(runService.steps(run)).thenReturn(steps);

        underTest.execute(run);

        verify(runService).completed(any(), any());
        verify(runService).recordException(eq(run), eq(EodCompletionStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodCompletionStep.CODE_STEP_UNCONFIGURED), anyString(), eq("EOD_MIRROR"));
    }

    @Test
    void aFailedStepStillConfiguredBlocks() {
        List<String> withMirror = new ArrayList<>(EodStepPlan.REQUIRED);
        withMirror.add("EOD_MIRROR");
        when(stepConfig.findAllByJobName(EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn(configured(withMirror));
        List<EodStep> steps = new ArrayList<>(allRequiredCompleted());
        EodStep mirror = EodStep.start(run, "EOD_MIRROR", NOW);
        mirror.setStatus(EodStep.STATUS_FAILED);
        steps.add(mirror);
        when(runService.steps(run)).thenReturn(steps);

        assertEquals(EodCompletionStep.CODE_INCOMPLETE, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
        verify(runService, never()).recordException(any(), any(), any(), eq(EodCompletionStep.CODE_STEP_UNCONFIGURED), any(), any());
    }

    private static List<BatchBusinessStep> configured(List<String> names) {
        List<BatchBusinessStep> rows = new ArrayList<>();
        for (String name : names) {
            BatchBusinessStep row = new BatchBusinessStep();
            row.setJobName(EodCloseOfBusinessTasklet.JOB_NAME);
            row.setStepName(name);
            rows.add(row);
        }
        return rows;
    }

    private List<EodStep> allRequiredCompleted() {
        List<EodStep> steps = new ArrayList<>();
        for (String name : EodStepPlan.REQUIRED) {
            steps.add(
                    EodCompletionStep.NAME.equals(name) ? EodStep.start(run, name, NOW) : EodStepTestSupport.completedStep(run, name, NOW));
        }
        return steps;
    }
}
