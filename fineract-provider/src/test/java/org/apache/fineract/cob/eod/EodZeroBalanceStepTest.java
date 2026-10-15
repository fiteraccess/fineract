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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodGlResult;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseZeroCheck;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseZeroCheckResult;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class EodZeroBalanceStepTest {

    private EodRunService runService;
    private SynapseTransactionClient synapse;
    private EodZeroBalanceStep underTest;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        synapse = mock(SynapseTransactionClient.class);
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodZeroBalanceStep(runService, EodStepTestSupport.fastProperties(), provider);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        for (String mirror : EodZeroBalanceStep.MIRROR_STEPS) {
            when(runService.findStep(run, mirror)).thenReturn(Optional.of(EodStepTestSupport.completedStep(run, mirror, NOW)));
        }
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void needsEveryMirrorJobCompleted() {
        when(runService.findStep(run, EodMirrorTaxStep.NAME)).thenReturn(Optional.empty());

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(AbstractEodStep.CODE_PREREQUISITE, thrown.getCode());
        assertTrue(thrown.getMessage().contains(EodMirrorTaxStep.NAME));
        verify(synapse, never()).runZeroCheck(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allClearCompletes_andKeepsTheChecks() {
        when(synapse.runZeroCheck(D)).thenReturn(zeroCheck("CLEAR", check(1, "EOD_PAIR", "DEPOSIT bridge", "FINERACT", "2901", null, "0", "0", "CLEAR"),
                check(2, "EOD_PAIR", "DEPOSIT bridge", "FLEXCUBE", "FLX-BRG", "001", "0", "0", "CLEAR")));

        underTest.execute(run);

        ArgumentCaptor<List<EodGlResult>> rows = ArgumentCaptor.forClass(List.class);
        verify(runService).replaceGlResults(eq(run), eq(EodZeroBalanceStep.NAME), rows.capture());
        EodGlResult fineract = rows.getValue().get(0);
        assertEquals("EOD_PAIR", fineract.getKind());
        assertEquals(1, fineract.getRowNo());
        assertEquals("FINERACT", fineract.getSide());
        assertEquals("2901", fineract.getFinBridgeGl());
        assertEquals("CLEAR", fineract.getCheckResult());
        EodGlResult flexcube = rows.getValue().get(1);
        assertEquals("FLX-BRG", flexcube.getFlexBridgeGl());
        assertEquals("001", flexcube.getFlexBridgeBranch());
        assertNull(flexcube.getFlexSourceBranch());
        verify(runService, never()).recordException(any(), anyString(), anyString(), anyString(), anyString(), any());
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        assertEquals("CLEAR", detail.getValue().get("status"));
        assertEquals(2, detail.getValue().get("checks"));
        assertEquals(2, detail.getValue().get("clear"));
        assertEquals(0, detail.getValue().get("fail"));
        assertEquals(0, detail.getValue().get("unverified"));
    }

    @Test
    void aFailAndAnUnverifiedAreTwoCriticalRows_andFailTheStep() {
        when(synapse.runZeroCheck(D)).thenReturn(zeroCheck("FAILED",
                check(1, "EOD_PAIR", "DEPOSIT bridge", "FINERACT", "2901", null, "-150.00", "-100.00", "FAIL"),
                check(2, "EOD_PAIR", "DEPOSIT bridge", "FLEXCUBE", "FLX-BRG", "001", "150.00", null, "UNVERIFIED"),
                check(3, "CBN_MIRROR", "CBN mirror", "FINERACT", "1990", null, "0", "0", "CLEAR")));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodZeroBalanceStep.CODE_ZERO_BALANCE_FAIL, thrown.getCode());
        assertTrue(thrown.getMessage().contains("1 failed, 1 unverified"));
        verify(runService).replaceGlResults(eq(run), eq(EodZeroBalanceStep.NAME), argThat(rows -> rows.size() == 3));
        verify(runService).recordException(eq(run), eq(EodZeroBalanceStep.NAME), eq(EodException.SEVERITY_CRITICAL),
                eq(EodZeroBalanceStep.CODE_ZERO_BALANCE_FAIL),
                eq("FAIL DEPOSIT bridge: gl=2901 side=FINERACT expected=-150.00 actual=-100.00 shortfall=-50.00"), eq("EOD_PAIR:2901:FINERACT"));
        verify(runService).recordException(eq(run), eq(EodZeroBalanceStep.NAME), eq(EodException.SEVERITY_CRITICAL),
                eq(EodZeroBalanceStep.CODE_ZERO_BALANCE_FAIL),
                eq("UNVERIFIED DEPOSIT bridge: gl=FLX-BRG branch=001 side=FLEXCUBE expected=150.00 actual=n/a shortfall=n/a"),
                eq("EOD_PAIR:FLX-BRG:FLEXCUBE"));
        verify(runService, times(2)).recordException(any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(runService, never()).completed(any(), any());
    }

    @Test
    void aRefusalNamesSynapsesReason() {
        when(synapse.runZeroCheck(D)).thenThrow(new SynapsePostingException(
                "Synapse zero-check date=2026-10-06 failed with HTTP 409 CONFLICT: {\"message\":\"TAX mirror run is POSTING\"}",
                new HttpClientErrorException(HttpStatus.CONFLICT, "Conflict", new byte[0], StandardCharsets.UTF_8)));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodZeroBalanceStep.CODE_ZERO_BALANCE_REFUSED, thrown.getCode());
        assertTrue(thrown.getMessage().contains("TAX mirror run is POSTING"));
        verify(synapse, times(1)).runZeroCheck(D);
    }

    @Test
    void anOutageIsAskedAgain() {
        when(synapse.runZeroCheck(D))
                .thenThrow(new SynapsePostingException("HTTP 503", new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
                .thenReturn(zeroCheck("CLEAR", check(1, "CBN_MIRROR", "CBN mirror", "FINERACT", "1990", null, "0", "0", "CLEAR")));

        underTest.execute(run);

        verify(synapse, times(2)).runZeroCheck(D);
        verify(runService).completed(any(), any());
    }

    private static SynapseZeroCheck zeroCheck(String status, SynapseZeroCheckResult... checks) {
        SynapseZeroCheck.Run checkRun = new SynapseZeroCheck.Run();
        checkRun.setBusinessDate(D);
        checkRun.setStatus(status);
        checkRun.setAttempts(1);
        SynapseZeroCheck zeroCheck = new SynapseZeroCheck();
        zeroCheck.setRun(checkRun);
        zeroCheck.setChecks(List.of(checks));
        return zeroCheck;
    }

    private static SynapseZeroCheckResult check(int checkNo, String kind, String name, String side, String glCode, String branch,
            String expected, String actual, String result) {
        SynapseZeroCheckResult check = new SynapseZeroCheckResult();
        check.setCheckNo(checkNo);
        check.setKind(kind);
        check.setName(name);
        check.setSide(side);
        check.setGlCode(glCode);
        check.setBranch(branch);
        check.setExpected(new BigDecimal(expected));
        check.setActual(actual == null ? null : new BigDecimal(actual));
        check.setResult(result);
        return check;
    }
}
