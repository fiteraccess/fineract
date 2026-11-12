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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYear;
import org.apache.fineract.organisation.financialcalendar.service.FinancialCalendarService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EodYearEndStepTest {

    private static final LocalDate LAST_WORKING_DAY = LocalDate.of(2026, 12, 30);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 12, 31);
    private static final LocalDate NEXT = LocalDate.of(2027, 1, 2);

    private EodRunService runService;
    private FinancialCalendarService calendar;
    private GLClosureRepository closures;
    private final Office headOffice = mock(Office.class);
    private final Office branch = mock(Office.class);
    private final FinancialYear fy2026 = year("FY2026", 2026);
    private EodYearEndStep underTest;
    private EodRun run;

    @BeforeEach
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        calendar = mock(FinancialCalendarService.class);
        closures = mock(GLClosureRepository.class);
        OfficeRepository offices = mock(OfficeRepository.class);
        when(headOffice.getId()).thenReturn(1L);
        when(headOffice.getName()).thenReturn("Head Office");
        when(branch.getId()).thenReturn(2L);
        when(branch.getName()).thenReturn("Ikeja");
        when(offices.findAll()).thenReturn(List.of(headOffice, branch));
        underTest = new EodYearEndStep(runService, EodStepTestSupport.fastProperties(), calendar, closures, offices);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        when(runService.findStep(run, EodMonthEndStep.NAME))
                .thenReturn(Optional.of(EodStepTestSupport.completedStep(run, EodMonthEndStep.NAME, NOW)));
        when(calendar.findYearCovering(D)).thenReturn(Optional.of(fy2026));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void needsMonthEndCompleted() {
        when(runService.findStep(run, EodMonthEndStep.NAME)).thenReturn(Optional.empty());

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(AbstractEodStep.CODE_PREREQUISITE, thrown.getCode());
        assertTrue(thrown.getMessage().contains(EodMonthEndStep.NAME));
        verify(calendar, never()).closeYear(any(), any());
    }

    @Test
    void aRollWithinTheYearChangesNothing() {
        underTest.execute(run);

        verify(calendar, never()).closeYear(any(), any());
        assertEquals(Map.of("yearChanged", false, "financialCycle", "FY2026"), completedDetail());
    }

    /** Month end has already raised the unknown-period warning for this day; year end only says why it did nothing. */
    @Test
    void aClosingDayNoYearCoversIsRecorded_withoutASecondWarning() {
        when(calendar.findYearCovering(D)).thenReturn(Optional.empty());

        underTest.execute(run);

        assertEquals(false, completedDetail().get("yearChanged"));
        verify(runService, never()).recordException(any(), any(), any(), any(), anyString(), any());
        verify(calendar, never()).closeYear(any(), any());
    }

    @Nested
    class LeavingTheYear {

        @BeforeEach
        void theLastWorkingDayRollsIntoNextYear() {
            run.setBusinessDate(LAST_WORKING_DAY);
            run.setNextBusinessDate(NEXT);
            when(calendar.findYearCovering(LAST_WORKING_DAY)).thenReturn(Optional.of(fy2026));
            when(calendar.findYearCovering(NEXT)).thenReturn(Optional.of(year("FY2027", 2027)));
            closedThrough(headOffice, YEAR_END);
            closedThrough(branch, YEAR_END);
        }

        @Test
        void confirmsEveryOfficeClosedThroughTheYearEnd_andMarksTheYearClosed() {
            underTest.execute(run);

            verify(calendar).closeYear(fy2026, LAST_WORKING_DAY);
            assertEquals(
                    Map.of("yearChanged", true, "financialCycle", "FY2026", "startDate", "2026-01-01", "endDate", "2026-12-31",
                            "officesClosedThrough", 2, "closedOn", "2026-12-30", "alreadyClosed", false, "nextFinancialCycle", "FY2027"),
                    completedDetail());
        }

        @Test
        void anOfficeNotClosedThroughTheYearEndFailsTheStep_namingIt() {
            closedThrough(branch, LocalDate.of(2026, 11, 30));

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

            assertEquals(EodYearEndStep.CODE_YEAR_NOT_CLOSED, thrown.getCode());
            assertTrue(thrown.getMessage().contains("[Ikeja]"), thrown.getMessage());
            verify(calendar, never()).closeYear(any(), any());
        }

        @Test
        void anOfficeNeverClosedFailsTheStep() {
            when(closures.getLatestGLClosureByBranch(1L)).thenReturn(null);

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

            assertTrue(thrown.getMessage().contains("[Head Office]"), thrown.getMessage());
        }

        @Test
        void aResumedRunFindsTheYearClosed_andWritesNothing() {
            fy2026.close(LAST_WORKING_DAY);

            underTest.execute(run);

            verify(calendar, never()).closeYear(any(), any());
            Map<String, Object> detail = completedDetail();
            assertEquals(true, detail.get("alreadyClosed"));
            assertEquals("2026-12-30", detail.get("closedOn"));
        }

        private void closedThrough(Office office, LocalDate closingDate) {
            when(closures.getLatestGLClosureByBranch(office.getId())).thenReturn(new GLClosure(office, closingDate, "EOD close"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> completedDetail() {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        return detail.getValue();
    }

    private static FinancialYear year(String cycle, int year) {
        FinancialYear financialYear = FinancialYear.create(cycle);
        financialYear.replace(null, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), true);
        return financialYear;
    }
}
