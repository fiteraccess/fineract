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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodGlResult;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseMirrorLeg;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseMirrorRun;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class EodMirrorStepTest {

    private EodRunService runService;
    private SynapseTransactionClient synapse;
    private ObjectProvider<SynapseTransactionClient> provider;
    private GLAccountRepository glAccounts;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        synapse = mock(SynapseTransactionClient.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        glAccounts = mock(GLAccountRepository.class);
        GLAccount bridge = new GLAccount();
        bridge.setGlCode("2901");
        when(glAccounts.findById(55L)).thenReturn(Optional.of(bridge));
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        completed(EodBillsGateStep.NAME);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Nested
    class Prerequisites {

        @Test
        void depositNeedsTheBillsGate() {
            when(runService.findStep(run, EodBillsGateStep.NAME)).thenReturn(Optional.empty());

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> deposit().execute(run));

            assertEquals(AbstractEodStep.CODE_PREREQUISITE, thrown.getCode());
            assertTrue(thrown.getMessage().contains(EodBillsGateStep.NAME));
            verify(synapse, never()).startMirrorRun(any(), anyString());
        }

        @Test
        void eachLaterJobNeedsTheMirrorBeforeIt() {
            assertPrerequisite(new EodMirrorIncomeStep(runService, EodStepTestSupport.fastProperties(), provider, glAccounts),
                    EodMirrorDepositStep.NAME);
            assertPrerequisite(new EodMirrorTaxStep(runService, EodStepTestSupport.fastProperties(), provider, glAccounts),
                    EodMirrorIncomeStep.NAME);
            assertPrerequisite(new EodMirrorInterestStep(runService, EodStepTestSupport.fastProperties(), provider, glAccounts),
                    EodMirrorTaxStep.NAME);
        }

        private void assertPrerequisite(EodMirrorStep step, String previous) {
            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> step.execute(run));

            assertEquals(AbstractEodStep.CODE_PREREQUISITE, thrown.getCode());
            assertTrue(thrown.getMessage().contains(previous), thrown.getMessage());
        }

        @Test
        void interestStartsItsOwnJobOnceTaxCompleted() {
            completed(EodMirrorTaxStep.NAME);
            when(synapse.getMirrorRun(D, "INTEREST")).thenReturn(view("COMPLETED", null, List.of()));

            new EodMirrorInterestStep(runService, EodStepTestSupport.fastProperties(), provider, glAccounts).execute(run);

            verify(synapse).startMirrorRun(D, "INTEREST");
            verify(runService).completed(any(), argThat(detail -> "INTEREST".equals(detail.get("job"))));
        }
    }

    @Nested
    class Start {

        @Test
        void aRefusalNamesSynapsesReason() {
            when(synapse.startMirrorRun(D, "DEPOSIT")).thenThrow(new SynapsePostingException(
                    "Synapse mirror-run start date=2026-10-06 job=DEPOSIT failed with HTTP 409 CONFLICT: {\"message\":\"mirroring is disabled\"}",
                    new HttpClientErrorException(HttpStatus.CONFLICT, "Conflict", new byte[0], StandardCharsets.UTF_8)));

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> deposit().execute(run));

            assertEquals(EodMirrorStep.CODE_MIRROR_REFUSED, thrown.getCode());
            assertTrue(thrown.getMessage().contains("mirroring is disabled"));
            verify(synapse, never()).getMirrorRun(any(), anyString());
        }

        @Test
        void aBadRequestIsARefusalToo() {
            when(synapse.startMirrorRun(D, "DEPOSIT")).thenThrow(new SynapsePostingException("HTTP 400 before the cutover",
                    new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", new byte[0], StandardCharsets.UTF_8)));

            assertEquals(EodMirrorStep.CODE_MIRROR_REFUSED, assertThrows(EodStepFailedException.class, () -> deposit().execute(run)).getCode());
        }

        @Test
        void anOutageFailsAsAPushError() {
            when(synapse.startMirrorRun(D, "DEPOSIT"))
                    .thenThrow(new SynapsePostingException("connection error", new ResourceAccessException("refused")));

            assertEquals(EodRolloverStep.CODE_SYNAPSE_PUSH,
                    assertThrows(EodStepFailedException.class, () -> deposit().execute(run)).getCode());
        }

        @Test
        void a5xxFailsAsAPushError() {
            when(synapse.startMirrorRun(D, "DEPOSIT")).thenThrow(
                    new SynapsePostingException("HTTP 503", new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)));

            assertEquals(EodRolloverStep.CODE_SYNAPSE_PUSH,
                    assertThrows(EodStepFailedException.class, () -> deposit().execute(run)).getCode());
        }
    }

    @Nested
    class Polling {

        @Test
        @SuppressWarnings("unchecked")
        void waitsThroughPlanningAndPosting_thenKeepsTheLegsAndFlagsDrift() {
            SynapseMirrorLeg posted = leg(1, "CUSTOMER_REGULAR_SAVINGS_DEPOSIT", "POSTED", "SENT", "NONE", "100.50");
            SynapseMirrorLeg drifted = leg(2, "CUSTOMER_GOAL_SAVINGS_DEPOSIT", "POSTED", "SENT", "DRIFT", "20.00");
            SynapseMirrorLeg skipped = leg(3, "CUSTOMER_FIXED_DEPOSIT", "SKIPPED", "SKIPPED", "NONE", "0");
            when(synapse.getMirrorRun(D, "DEPOSIT")).thenReturn(view("PLANNING", "waiting for the rollup", List.of()),
                    view("POSTING", null, List.of(posted, leg(2, "CUSTOMER_GOAL_SAVINGS_DEPOSIT", "PENDING", "PENDING", "NONE", "20.00"))),
                    view("COMPLETED", null, List.of(posted, drifted, skipped)));

            deposit().execute(run);

            verify(synapse).startMirrorRun(D, "DEPOSIT");
            verify(runService).waiting(any(), argThat(detail -> "PLANNING".equals(detail.get("status"))
                    && "waiting for the rollup".equals(detail.get("lastError")) && Integer.valueOf(0).equals(detail.get("legs"))));
            verify(runService).waiting(any(), argThat(detail -> "POSTING".equals(detail.get("status"))
                    && Long.valueOf(1).equals(detail.get("finPosted")) && Long.valueOf(1).equals(detail.get("flexSent"))));

            ArgumentCaptor<List<EodGlResult>> rows = ArgumentCaptor.forClass(List.class);
            verify(runService).replaceGlResults(eq(run), eq(EodMirrorDepositStep.NAME), rows.capture());
            assertEquals(3, rows.getValue().size());
            EodGlResult first = rows.getValue().getFirst();
            assertEquals(EodGlResult.KIND_MIRROR_LEG, first.getKind());
            assertEquals(1, first.getRowNo());
            assertEquals("CUSTOMER_REGULAR_SAVINGS_DEPOSIT", first.getLogicalName());
            assertEquals("2001", first.getFinSourceGl());
            assertEquals("2901", first.getFinBridgeGl());
            assertEquals("FLX-SRC", first.getFlexSourceGl());
            assertEquals("FLX-BRG", first.getFlexBridgeGl());
            assertEquals("001", first.getFlexSourceBranch());
            assertEquals("099", first.getFlexBridgeBranch(), "the bridge keeps its own branch");
            assertEquals("CREDIT", first.getNormalSide());
            assertEquals(Boolean.FALSE, first.getFlipped());
            assertEquals(new BigDecimal("100.50"), first.getAmount());
            assertEquals("POSTED", first.getFinStatus());
            assertEquals("SENT", first.getFlexStatus());
            assertEquals("NONE", first.getDriftStatus());
            assertEquals("EOD-2026-10-06-DEPOSIT-1", first.getReference());

            verify(runService).recordException(eq(run), eq(EodMirrorDepositStep.NAME), eq(EodException.SEVERITY_WARN),
                    eq(EodMirrorStep.CODE_MIRROR_DRIFT), argThat(message -> message.contains("CUSTOMER_GOAL_SAVINGS_DEPOSIT")),
                    eq("EOD-2026-10-06-DEPOSIT-2"));
            verify(runService, times(1)).recordException(any(), anyString(), anyString(), anyString(), anyString(), any());

            ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
            verify(runService).completed(any(), detail.capture());
            assertEquals("DEPOSIT", detail.getValue().get("job"));
            assertEquals(3, detail.getValue().get("legs"));
            assertEquals(new BigDecimal("120.50"), detail.getValue().get("amountTotal"));
            assertEquals(1L, detail.getValue().get("skipped"));
            assertEquals(1, detail.getValue().get("drift"));
            assertEquals(3, detail.getValue().get("polls"));
        }

        /**
         * Synapse sends a net-negative movement as a positive amount with flipped; the row and the total keep its sign.
         */
        @Test
        @SuppressWarnings("unchecked")
        void aFlippedLegIsKeptNegativeWithItsDirection() {
            SynapseMirrorLeg inflow = leg(1, "CUSTOMER_REGULAR_SAVINGS_DEPOSIT", "POSTED", "SENT", "NONE", "100.00");
            SynapseMirrorLeg outflow = leg(2, "CUSTOMER_GOAL_SAVINGS_DEPOSIT", "POSTED", "SENT", "NONE", "30.00");
            outflow.setFlipped(true);
            when(synapse.getMirrorRun(D, "DEPOSIT")).thenReturn(view("COMPLETED", null, List.of(inflow, outflow)));

            deposit().execute(run);

            ArgumentCaptor<List<EodGlResult>> rows = ArgumentCaptor.forClass(List.class);
            verify(runService).replaceGlResults(eq(run), eq(EodMirrorDepositStep.NAME), rows.capture());
            EodGlResult flipped = rows.getValue().get(1);
            assertEquals(new BigDecimal("-30.00"), flipped.getAmount());
            assertEquals(Boolean.TRUE, flipped.getFlipped());
            assertEquals("CREDIT", flipped.getNormalSide());
            ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
            verify(runService).completed(any(), detail.capture());
            assertEquals(new BigDecimal("70.00"), detail.getValue().get("amountTotal"));
        }

        @Test
        void aFailedRunNamesItsErrorAndTheFailedLegs() {
            SynapseMirrorLeg failed = leg(2, "CUSTOMER_GOAL_SAVINGS_DEPOSIT", "FAILED", "PENDING", "NONE", "20.00");
            failed.setLastError("GL 2002 is disabled");
            when(synapse.getMirrorRun(D, "DEPOSIT")).thenReturn(view("FAILED", "1 leg failed",
                    List.of(leg(1, "CUSTOMER_REGULAR_SAVINGS_DEPOSIT", "POSTED", "SENT", "NONE", "1"), failed)));

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> deposit().execute(run));

            assertEquals(EodMirrorStep.CODE_MIRROR_FAILED, thrown.getCode());
            assertTrue(thrown.getMessage().contains("1 leg failed"));
            assertTrue(thrown.getMessage().contains("CUSTOMER_GOAL_SAVINGS_DEPOSIT: GL 2002 is disabled"));
            assertTrue(!thrown.getMessage().contains("CUSTOMER_REGULAR_SAVINGS_DEPOSIT"));
            verify(runService, never()).replaceGlResults(any(), anyString(), any());
        }

        @Test
        void aRunNeedingReconciliationListsTheRowsToReconcile() {
            SynapseMirrorLeg unknown = leg(4, "CUSTOMER_GOAL_SAVINGS_DEPOSIT", "POSTING", "PENDING", "NONE", "20.00");
            when(synapse.getMirrorRun(D, "DEPOSIT")).thenReturn(view("NEEDS_RECONCILIATION", "posting outcome unknown",
                    List.of(leg(1, "CUSTOMER_REGULAR_SAVINGS_DEPOSIT", "POSTED", "SENT", "NONE", "1"), unknown)));

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> deposit().execute(run));

            assertEquals(EodMirrorStep.CODE_MIRROR_RECONCILE, thrown.getCode());
            assertTrue(thrown.getMessage().contains("row 4 CUSTOMER_GOAL_SAVINGS_DEPOSIT POSTING"));
            assertTrue(!thrown.getMessage().contains("row 1"));
        }

        @Test
        void a5xxDuringThePollIsRetried() {
            when(synapse.getMirrorRun(D, "DEPOSIT"))
                    .thenThrow(new SynapsePostingException("Synapse mirror run read failed with HTTP 502",
                            new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))
                    .thenReturn(view("COMPLETED", null, List.of()));

            deposit().execute(run);

            verify(synapse, times(2)).getMirrorRun(D, "DEPOSIT");
            verify(runService).waiting(any(), argThat(detail -> String.valueOf(detail.get("lastError")).contains("502")));
            verify(runService).completed(any(), any());
        }
    }

    private EodMirrorDepositStep deposit() {
        return new EodMirrorDepositStep(runService, EodStepTestSupport.fastProperties(), provider, glAccounts);
    }

    private void completed(String stepName) {
        when(runService.findStep(run, stepName)).thenReturn(Optional.of(EodStepTestSupport.completedStep(run, stepName, NOW)));
    }

    private static SynapseMirrorRun view(String status, String lastError, List<SynapseMirrorLeg> legs) {
        SynapseMirrorRun.Run mirror = new SynapseMirrorRun.Run();
        mirror.setId(1L);
        mirror.setBusinessDate(D);
        mirror.setJob("DEPOSIT");
        mirror.setStatus(status);
        mirror.setLastError(lastError);
        SynapseMirrorRun view = new SynapseMirrorRun();
        view.setRun(mirror);
        view.setLegs(legs);
        return view;
    }

    private static SynapseMirrorLeg leg(int rowNo, String logicalName, String finStatus, String flexStatus, String driftStatus,
            String amount) {
        SynapseMirrorLeg leg = new SynapseMirrorLeg();
        leg.setRowNo(rowNo);
        leg.setLogicalName(logicalName);
        leg.setFinSourceGlCode("2001");
        leg.setFinBridgeGlId(55L);
        leg.setFlexSource(flexGl("FLX-SRC", "001"));
        leg.setFlexBridge(flexGl("FLX-BRG", "099"));
        leg.setNormalSide("CREDIT");
        leg.setAmount(new BigDecimal(amount));
        leg.setReference("EOD-2026-10-06-DEPOSIT-" + rowNo);
        leg.setFinStatus(finStatus);
        leg.setFlexStatus(flexStatus);
        leg.setDriftStatus(driftStatus);
        return leg;
    }

    private static SynapseMirrorLeg.FlexcubeGl flexGl(String gl, String branch) {
        SynapseMirrorLeg.FlexcubeGl flex = new SynapseMirrorLeg.FlexcubeGl();
        flex.setGl(gl);
        flex.setBranch(branch);
        return flex;
    }
}
