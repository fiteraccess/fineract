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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.exception.MultiException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.service.SavingsAccrualWritePlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class EodAccrualStepTest {

    private EodRunService runService;
    private SavingsAccrualWritePlatformService accrual;
    private JdbcTemplate jdbc;
    private EodAccrualStep underTest;
    private EodRun run;

    @BeforeEach
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        accrual = mock(SavingsAccrualWritePlatformService.class);
        jdbc = mock(JdbcTemplate.class);
        underTest = new EodAccrualStep(runService, EodStepTestSupport.fastProperties(), accrual, jdbc);
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
    void accruesTheClosingDay_andRecordsWhatItBookedPerGl() throws Exception {
        when(jdbc.queryForObject(EodAccrualStep.MAX_JOURNAL_ENTRY_ID_SQL, Long.class)).thenReturn(5000L);
        when(jdbc.queryForList(EodAccrualStep.ACCRUED_BY_GL_SQL, 5000L))
                .thenReturn(List.of(Map.of("gl_code", "2201", "name", "Accrued Interest Payable", "total", new BigDecimal("150.25")),
                        Map.of("gl_code", "2202", "name", "Accrued Interest Payable - Goals", "total", new BigDecimal("9.75"))));

        underTest.execute(run);

        InOrder order = inOrder(jdbc, accrual);
        order.verify(jdbc).queryForObject(EodAccrualStep.MAX_JOURNAL_ENTRY_ID_SQL, Long.class);
        order.verify(accrual).addAccrualEntries(D);
        order.verify(jdbc).queryForList(EodAccrualStep.ACCRUED_BY_GL_SQL, 5000L);
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        assertEquals(new BigDecimal("160.00"), detail.getValue().get("accruedTotal"));
        assertEquals(2, ((List<?>) detail.getValue().get("accruedByGl")).size());
    }

    @Test
    void beforeAHolidayGapAccruesEveryDayUpToTheNextBusinessDate() throws Exception {
        run.setNextBusinessDate(D.plusDays(4));
        when(jdbc.queryForObject(EodAccrualStep.MAX_JOURNAL_ENTRY_ID_SQL, Long.class)).thenReturn(5000L);
        when(jdbc.queryForList(EodAccrualStep.ACCRUED_BY_GL_SQL, 5000L)).thenReturn(List.of());

        underTest.execute(run);

        verify(accrual).addAccrualEntries(D.plusDays(3));
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        assertEquals(D.toString(), detail.getValue().get("fromDate"));
        assertEquals(D.plusDays(3).toString(), detail.getValue().get("tillDate"));
    }

    /** The chain runs every step in the COB context, whose date is the closing day: accrual would stop there. */
    @Test
    void accruesAsOfTheNextBusinessDate_thoughTheStepRunsInTheCobContext() throws Exception {
        LocalDate next = D.plusDays(4);
        run.setNextBusinessDate(next);
        EodRolloverStep.useClosingDates(D, next);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);
        when(jdbc.queryForObject(EodAccrualStep.MAX_JOURNAL_ENTRY_ID_SQL, Long.class)).thenReturn(5000L);
        when(jdbc.queryForList(EodAccrualStep.ACCRUED_BY_GL_SQL, 5000L)).thenReturn(List.of());
        List<LocalDate> cutOffSeenByAccrual = new ArrayList<>();
        doAnswer(invocation -> {
            cutOffSeenByAccrual.add(DateUtils.getBusinessLocalDate());
            return null;
        }).when(accrual).addAccrualEntries(D.plusDays(3));

        underTest.execute(run);

        assertEquals(List.of(next), cutOffSeenByAccrual);
        assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    @Test
    void aFailedAccrualStillHandsTheCobContextBack() throws Exception {
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);
        doThrow(new MultiException(List.of(new RuntimeException("closed period")))).when(accrual).addAccrualEntries(D);

        assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    /**
     * Catch-up days and a recalculation's reversals are journal entries too; the SQL nets them, never filters by date.
     */
    @Test
    void theTotalIsTheNetMovementOnTheLiabilityGls_notJustTheClosingDaysCredits() {
        assertTrue(EodAccrualStep.ACCRUED_BY_GL_SQL.contains("je.id > ?"));
        assertTrue(EodAccrualStep.ACCRUED_BY_GL_SQL.contains("CASE WHEN je.type_enum = 1 THEN je.amount ELSE -je.amount END"));
        assertTrue(EodAccrualStep.ACCRUED_BY_GL_SQL.contains("gl.classification_enum = 2"));
        assertFalse(EodAccrualStep.ACCRUED_BY_GL_SQL.contains("transaction_date"));
    }

    @Test
    void requiresTheDrainedGate() throws Exception {
        when(runService.findStep(run, EodReplayDrainGateStep.NAME)).thenReturn(Optional.empty());

        assertEquals(AbstractEodStep.CODE_PREREQUISITE, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
        verify(accrual, never()).addAccrualEntries(any());
    }

    @Test
    void anAccountThatFailedToAccrueFailsTheStep() throws Exception {
        doThrow(new MultiException(List.of(new RuntimeException("optimistic lock")))).when(accrual).addAccrualEntries(D);

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodAccrualStep.CODE_ACCRUAL, thrown.getCode());
        verify(runService).failed(any(), eq(EodAccrualStep.CODE_ACCRUAL), any());
    }
}
