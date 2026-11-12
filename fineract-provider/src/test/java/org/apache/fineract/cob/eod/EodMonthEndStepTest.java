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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.closure.service.GLClosureWritePlatformService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseEodCalendar;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class EodMonthEndStepTest {

    private EodRunService runService;
    private SynapseTransactionClient synapse;
    private GLClosureWritePlatformService closures;
    private final Office headOffice = mock(Office.class);
    private final Office branch = mock(Office.class);
    private EodMonthEndStep underTest;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        synapse = mock(SynapseTransactionClient.class);
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        closures = mock(GLClosureWritePlatformService.class);
        OfficeRepository offices = mock(OfficeRepository.class);
        when(offices.findAll()).thenReturn(List.of(headOffice, branch));
        underTest = new EodMonthEndStep(runService, EodStepTestSupport.fastProperties(), provider, closures, offices);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        periodIs(october());
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void aRollWithinThePeriodClosesNothing() {
        underTest.execute(run);

        verify(closures, never()).closeOfficeThrough(any(), any(), anyString());
        assertEquals(Map.of("periodChanged", false, "period", "M10", "financialCycle", "FY2026"), completedDetail());
    }

    @Test
    void leavingTheMonthClosesEveryOfficeThroughItsLastDay() {
        closing(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1));
        when(closures.closeOfficeThrough(headOffice, LocalDate.of(2026, 10, 31), "EOD close of M10 FY2026")).thenReturn(true);
        when(closures.closeOfficeThrough(branch, LocalDate.of(2026, 10, 31), "EOD close of M10 FY2026")).thenReturn(true);

        underTest.execute(run);

        assertEquals(Map.of("periodChanged", true, "period", "M10", "financialCycle", "FY2026", "closedThrough", "2026-10-31",
                "officesClosed", 2, "officesAlreadyClosed", 0), completedDetail());
    }

    @Test
    void whenTheMonthEndsOnHolidaysTheLastWorkingDayClosesItThroughTheMonthEnd() {
        closing(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2));
        periodIs(december());

        underTest.execute(run);

        verify(closures).closeOfficeThrough(headOffice, LocalDate.of(2026, 12, 31), "EOD close of M12 FY2026");
        verify(closures).closeOfficeThrough(branch, LocalDate.of(2026, 12, 31), "EOD close of M12 FY2026");
    }

    /**
     * The chain runs every step in the COB context, whose date is the closing day: 31 Dec would be a future closure.
     */
    @Test
    void aMonthEndingOnAHolidayIsClosedAsOfTheNextBusinessDate_thoughTheStepRunsInTheCobContext() {
        LocalDate lastWorkingDay = LocalDate.of(2026, 12, 30);
        LocalDate next = LocalDate.of(2027, 1, 2);
        closing(lastWorkingDay, next);
        periodIs(december());
        EodRolloverStep.useClosingDates(lastWorkingDay, next);
        ThreadLocalContextUtil.setActionContext(ActionContext.COB);
        List<LocalDate> todaySeenByTheClosure = new ArrayList<>();
        when(closures.closeOfficeThrough(any(), eq(LocalDate.of(2026, 12, 31)), eq("EOD close of M12 FY2026"))).thenAnswer(invocation -> {
            todaySeenByTheClosure.add(DateUtils.getBusinessLocalDate());
            return true;
        });

        underTest.execute(run);

        assertEquals(List.of(next, next), todaySeenByTheClosure);
        assertEquals(ActionContext.COB, ThreadLocalContextUtil.getActionContext());
    }

    /** A resumed run finds the closures it made before it failed; the step completes and says so. */
    @Test
    void aResumedRunCountsTheOfficesAlreadyClosed() {
        closing(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1));

        underTest.execute(run);

        Map<String, Object> detail = completedDetail();
        assertEquals(0, detail.get("officesClosed"));
        assertEquals(2, detail.get("officesAlreadyClosed"));
    }

    @Test
    void aClosingDayNoPeriodCoversIsReported_andClosesNothing() {
        periodIs(null);

        underTest.execute(run);

        verify(runService).recordException(eq(run), eq(EodMonthEndStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodMonthEndStep.CODE_PERIOD_UNKNOWN), anyString(), any());
        verify(closures, never()).closeOfficeThrough(any(), any(), anyString());
        assertEquals(false, completedDetail().get("periodChanged"));
    }

    /** Nothing may be closed on a guess: without Synapse's answer the step fails and a resume asks again. */
    @Test
    void aCalendarSynapseCannotAnswerFailsTheStep_andClosesNothing() {
        closing(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1));
        when(synapse.getEodCalendar(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)))
                .thenThrow(new SynapsePostingException("connection error", null));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(AbstractEodStep.CODE_CALENDAR_UNAVAILABLE, thrown.getCode());
        verify(closures, never()).closeOfficeThrough(any(), any(), anyString());
    }

    /** Sets the run's dates; the closing day is in October unless {@link #periodIs} says otherwise afterwards. */
    private void closing(LocalDate closing, LocalDate next) {
        run.setBusinessDate(closing);
        run.setNextBusinessDate(next);
        periodIs(october());
    }

    /** Synapse's answer for the run's closing day and the date it rolled to. */
    private void periodIs(SynapseEodCalendar.Period period) {
        when(synapse.getEodCalendar(run.getBusinessDate(), run.rolledTo()))
                .thenReturn(new SynapseEodCalendar(run.getBusinessDate(), run.rolledTo(), period, null, null, null, List.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> completedDetail() {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(runService).completed(any(), detail.capture());
        return detail.getValue();
    }

    private static SynapseEodCalendar.Period october() {
        return new SynapseEodCalendar.Period("FY2026", "M10", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }

    private static SynapseEodCalendar.Period december() {
        return new SynapseEodCalendar.Period("FY2026", "M12", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
    }
}
