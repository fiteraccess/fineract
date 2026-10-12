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

import static org.apache.fineract.cob.eod.EodStepTestSupport.D;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseReplayStatus;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class EodReplayDrainGateStepTest {

    private EodRunService runService;
    private SynapseTransactionClient synapse;
    private EodReplayDrainGateStep underTest;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        synapse = mock(SynapseTransactionClient.class);
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodReplayDrainGateStep(runService, EodStepTestSupport.fastProperties(), provider);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        // The grace wait compares the rollover's completion with the real clock: it must lie in the actual past.
        when(runService.findStep(run, EodRolloverStep.NAME)).thenReturn(Optional
                .of(EodStepTestSupport.completedStep(run, EodRolloverStep.NAME, DateUtils.getLocalDateTimeOfTenant().minusDays(1))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void requiresTheRollover() {
        when(runService.findStep(run, EodRolloverStep.NAME)).thenReturn(Optional.empty());

        assertEquals(AbstractEodStep.CODE_PREREQUISITE, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
        verify(synapse, never()).getReplayStatus(any());
    }

    @Test
    void waitsWhileAnythingIsInFlight_andCompletesOnceDrained() {
        when(synapse.getReplayStatus(D)).thenReturn(status(3, false, List.of(), List.of()), status(0, true, List.of(), List.of()));

        underTest.execute(run);

        verify(synapse, times(2)).getReplayStatus(D);
        verify(runService).waiting(any(), any());
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        assertEquals(true, detail.getValue().get("drained"));
        assertEquals(2, detail.getValue().get("polls"));
    }

    @Test
    void aNewFailureFailsTheRunAtOnce_namingTheReference() {
        when(synapse.getReplayStatus(D)).thenReturn(status(2, false, List.of(failure("REF-9", "DEPOSIT", "FAILED_POST_TO_FINERACT")), List.of()));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodReplayDrainGateStep.CODE_REPLAY_FAILED, thrown.getCode());
        assertTrue(thrown.getMessage().contains("REF-9"));
    }

    @Test
    void aNewFailureBlocksEvenWhenTheSampleIsEmpty() {
        SynapseReplayStatus status = status(0, true, List.of(), List.of());
        status.setFailedNewCount(1);
        when(synapse.getReplayStatus(D)).thenReturn(status);

        assertEquals(EodReplayDrainGateStep.CODE_REPLAY_FAILED,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    void legacyFailuresBecomeExceptionRows_andDoNotBlock() {
        when(synapse.getReplayStatus(D)).thenReturn(status(0, true, List.of(), List.of(failure("OLD-1", "WITHDRAWAL", "UNKNOWN"))));

        underTest.execute(run);

        verify(runService).recordException(eq(run), eq(EodReplayDrainGateStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodReplayDrainGateStep.CODE_REPLAY_LEGACY), anyString(), eq("OLD-1"));
        verify(runService).completed(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverDrainingTimesOut() {
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodReplayDrainGateStep(runService, EodStepTestSupport.properties(0), provider);
        when(synapse.getReplayStatus(D)).thenReturn(status(1, false, List.of(), List.of()));

        assertEquals(AbstractEodStep.CODE_TIMEOUT, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    private static SynapseReplayStatus status(long inFlight, boolean drained, List<SynapseReplayStatus.Failure> failedNew,
            List<SynapseReplayStatus.Failure> failedLegacy) {
        SynapseReplayStatus status = new SynapseReplayStatus();
        status.setBusinessDate(D);
        SynapseReplayStatus.InFlight flight = new SynapseReplayStatus.InFlight();
        flight.setPendingTx(inFlight);
        flight.setTotal(inFlight);
        status.setInFlight(flight);
        status.setFailedNewCount(failedNew.size());
        status.setFailedNew(failedNew);
        status.setFailedLegacyCount(failedLegacy.size());
        status.setFailedLegacy(failedLegacy);
        status.setDrained(drained);
        status.setCutoffSource("NONE");
        return status;
    }

    private static SynapseReplayStatus.Failure failure(String reference, String type, String rowStatus) {
        SynapseReplayStatus.Failure failure = new SynapseReplayStatus.Failure();
        failure.setReference(reference);
        failure.setSource("TX");
        failure.setType(type);
        failure.setStatus(rowStatus);
        failure.setTransactionDate(D);
        return failure;
    }
}
