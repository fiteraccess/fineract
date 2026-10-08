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
import static org.apache.fineract.cob.eod.EodStepTestSupport.NOW;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseSettlementStatus;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class EodBillsGateStepTest {

    private EodRunService runService;
    private SynapseTransactionClient synapse;
    private EodBillsGateStep underTest;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        synapse = mock(SynapseTransactionClient.class);
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodBillsGateStep(runService, EodStepTestSupport.fastProperties(), provider);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        when(runService.findStep(run, EodReplayDrainGateStep.NAME))
                .thenReturn(Optional.of(EodStepTestSupport.completedStep(run, EodReplayDrainGateStep.NAME, NOW)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void waitsWhileABatchIsInFlight_andCompletesOnceSettled() {
        when(synapse.getSettlementStatus(D)).thenReturn(status(1, 2, List.of(), List.of(), List.of("b-1"), false),
                status(0, 2, List.of(), List.of(), List.of(), true));

        underTest.execute(run);

        verify(synapse, times(2)).getSettlementStatus(D);
        verify(runService).completed(any(), any());
        verify(runService, never()).recordException(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    /** End of cycle never blocks EOD: Synapse answers settled with the batch still awaiting it, the gate completes. */
    @Test
    void aBatchAwaitingItsEndOfCycleDoesNotHoldTheDay() {
        when(synapse.getSettlementStatus(D)).thenReturn(status(0, 1, List.of(), List.of(), List.of("b-awaiting"), true));

        underTest.execute(run);

        verify(synapse).getSettlementStatus(D);
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        assertEquals(List.of("b-awaiting"), detail.getValue().get("awaitingEoc"));
    }

    @Test
    void aFailedBatchLoadedAfterTheCutoffFailsTheRun_namingIt() {
        when(synapse.getSettlementStatus(D))
                .thenReturn(status(0, 1, List.of(failure("b-bad", "NEEDS_RECONCILIATION", "amount mismatch")), List.of(), List.of(), false));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodBillsGateStep.CODE_BILLS_FAILED, thrown.getCode());
        assertTrue(thrown.getMessage().contains("b-bad"));
    }

    @Test
    void aNewFailureBlocksEvenWhenTheSampleIsEmpty() {
        SynapseSettlementStatus status = status(0, 1, List.of(), List.of(), List.of(), false);
        status.setFailedNewCount(3);
        when(synapse.getSettlementStatus(D)).thenReturn(status);

        assertEquals(EodBillsGateStep.CODE_BILLS_FAILED,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    void aLegacyFailureIsReportedAsAWarnRow_andDoesNotBlock() {
        when(synapse.getSettlementStatus(D))
                .thenReturn(status(0, 1, List.of(), List.of(failure("b-old", "FAILED", "bad file")), List.of(), true));

        underTest.execute(run);

        verify(runService).recordException(eq(run), eq(EodBillsGateStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodBillsGateStep.CODE_BILLS_LEGACY_FAILED), eq("Bills batch FAILED loaded 2026-10-02: bad file"), eq("b-old"));
        verify(runService).completed(any(), any());
    }

    @Test
    void noBillsFileForTheDayIsAnExceptionRow_notAFailure() {
        when(synapse.getSettlementStatus(D)).thenReturn(status(0, 0, List.of(), List.of(), List.of(), true));

        underTest.execute(run);

        verify(runService).recordException(eq(run), eq(EodBillsGateStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodBillsGateStep.CODE_BILLS_NO_FILE), anyString(), any());
        verify(runService).completed(any(), any());
    }

    private static SynapseSettlementStatus.BatchFailure failure(String batchId, String status, String lastError) {
        SynapseSettlementStatus.BatchFailure failure = new SynapseSettlementStatus.BatchFailure();
        failure.setBatchId(batchId);
        failure.setStatus(status);
        failure.setLoadedDate(LocalDate.of(2026, 10, 2));
        failure.setLastError(lastError);
        return failure;
    }

    private static SynapseSettlementStatus status(long inFlight, long loaded, List<SynapseSettlementStatus.BatchFailure> failedNew,
            List<SynapseSettlementStatus.BatchFailure> failedLegacy, List<String> awaitingEoc, boolean settled) {
        SynapseSettlementStatus status = new SynapseSettlementStatus();
        status.setBusinessDate(D);
        status.setRail("BILLS");
        status.setByStatus(Map.of("COMPLETED", loaded));
        status.setInFlight(inFlight);
        status.setFailedNewCount(failedNew.size());
        status.setFailedNew(failedNew);
        status.setFailedLegacyCount(failedLegacy.size());
        status.setFailedLegacy(failedLegacy);
        status.setAwaitingEoc(awaitingEoc);
        status.setLoadedForDate(loaded);
        status.setSettled(settled);
        return status;
    }
}
