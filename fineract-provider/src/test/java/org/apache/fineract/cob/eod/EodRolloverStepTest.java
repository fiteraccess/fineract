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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.businessdate.data.service.BusinessDateDTO;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.businessdate.exception.BusinessDateActionException;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialPeriod;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYear;
import org.apache.fineract.organisation.financialcalendar.service.FinancialCalendarService;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBusinessDateResponse;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EodRolloverStepTest {

    private static final LocalDate NEXT = D.plusDays(1);

    private EodRunService runService;
    private ConfigurationDomainService configuration;
    private BusinessDateReadPlatformService businessDateRead;
    private BusinessDateWritePlatformService businessDateWrite;
    private SynapseTransactionClient synapse;
    private NextBusinessDate nextBusinessDate;
    private FinancialCalendarService calendar;
    private EodRolloverStep underTest;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        configuration = mock(ConfigurationDomainService.class);
        businessDateRead = mock(BusinessDateReadPlatformService.class);
        businessDateWrite = mock(BusinessDateWritePlatformService.class);
        synapse = mock(SynapseTransactionClient.class);
        nextBusinessDate = mock(NextBusinessDate.class);
        calendar = mock(FinancialCalendarService.class);
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodRolloverStep(runService, EodStepTestSupport.fastProperties(), configuration, businessDateRead, businessDateWrite,
                provider, nextBusinessDate, calendar);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        when(configuration.isBusinessDateEnabled()).thenReturn(true);
        when(configuration.isCOBDateAdjustmentEnabled()).thenReturn(true);
        fineractIsOn(D);
        rollsTo(D, NEXT);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void advancesFineractFirst_thenPushesSynapse_andClosesDInTheRunsContext() {
        underTest.execute(run);

        verify(runService).saveNextBusinessDate(run, NEXT);
        verify(businessDateWrite).updateBusinessDate(eq(moveTo(NEXT)));
        verify(synapse).postBusinessDate(NEXT, D);
        assertEquals(NEXT, ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
        assertEquals(D, ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE));
        verify(runService).completed(any(), any());
        verify(runService, never()).recordException(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Nested
    class HolidaySkip {

        private final LocalDate christmasEve = LocalDate.of(2026, 12, 24);
        private final LocalDate afterBoxingDay = LocalDate.of(2026, 12, 28);

        @BeforeEach
        void closingChristmasEve() {
            run.setBusinessDate(christmasEve);
            fineractIsOn(christmasEve);
            rollsTo(christmasEve, afterBoxingDay);
        }

        @Test
        void rollsPastTheHolidays_andPushesTheDayItClosed() {
            underTest.execute(run);

            verify(businessDateWrite).updateBusinessDate(eq(moveTo(afterBoxingDay)));
            verify(synapse).postBusinessDate(afterBoxingDay, christmasEve);
            assertEquals(afterBoxingDay, ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
            assertEquals(christmasEve, ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE));
        }

        @Test
        void aResumedRunRollsToTheDateItFixed_withoutResolvingAgain() {
            run.setNextBusinessDate(afterBoxingDay);
            fineractIsOn(afterBoxingDay);

            underTest.execute(run);

            verify(nextBusinessDate, never()).after(any());
            verify(runService, never()).saveNextBusinessDate(any(), any());
            verify(businessDateWrite, never()).updateBusinessDate(any());
            verify(synapse).postBusinessDate(afterBoxingDay, christmasEve);
        }
    }

    @Nested
    class CalendarNotReady {

        @Test
        void aNextDateWithoutAPeriodHalts_namingIt_andDoesNotMove() {
            LocalDate firstOfJuly = LocalDate.of(2026, 7, 1);
            run.setBusinessDate(firstOfJuly.minusDays(1));
            fineractIsOn(firstOfJuly.minusDays(1));
            when(nextBusinessDate.after(firstOfJuly.minusDays(1))).thenReturn(firstOfJuly);
            when(calendar.findPeriodCovering(firstOfJuly)).thenReturn(Optional.empty());

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

            assertEquals(EodRolloverStep.CODE_PERIOD_MISSING, thrown.getCode());
            assertTrue(thrown.getMessage().contains("M07"), thrown.getMessage());
            assertNothingMoved();
        }

        @Test
        void theRollIntoANewYearHaltsWhenThatYearIsNotReady() {
            LocalDate newYearsEve = LocalDate.of(2026, 12, 31);
            run.setBusinessDate(newYearsEve);
            fineractIsOn(newYearsEve);
            when(nextBusinessDate.after(newYearsEve)).thenReturn(LocalDate.of(2027, 1, 1));
            when(calendar.findYearReadinessProblems(2027)).thenReturn(List.of("FY2027 holidays are not confirmed"));

            EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

            assertEquals(EodRolloverStep.CODE_YEAR_NOT_READY, thrown.getCode());
            assertTrue(thrown.getMessage().contains("FY2027 holidays are not confirmed"), thrown.getMessage());
            assertNothingMoved();
        }

        @Test
        void aHolidayOnNewYearsDayStillChecksTheNewYear_andRollsToTheSecond() {
            LocalDate newYearsEve = LocalDate.of(2026, 12, 31);
            LocalDate second = LocalDate.of(2027, 1, 2);
            run.setBusinessDate(newYearsEve);
            fineractIsOn(newYearsEve);
            rollsTo(newYearsEve, second);
            when(calendar.findYearReadinessProblems(2027)).thenReturn(List.of());

            underTest.execute(run);

            verify(calendar).findYearReadinessProblems(2027);
            verify(businessDateWrite).updateBusinessDate(eq(moveTo(second)));
        }

        @Test
        void aRollWithinTheYearDoesNotCheckYearReadiness() {
            underTest.execute(run);

            verify(calendar, never()).findYearReadinessProblems(org.mockito.ArgumentMatchers.anyInt());
        }

        private void assertNothingMoved() {
            verify(runService, never()).saveNextBusinessDate(any(), any());
            verify(businessDateWrite, never()).updateBusinessDate(any());
            verify(synapse, never()).postBusinessDate(any(), any());
        }
    }

    @Test
    void aResumedRunFindsFineractAlreadyOnTheNextDay_andOnlyRepeatsThePush() {
        fineractIsOn(NEXT);

        underTest.execute(run);

        verify(businessDateWrite, never()).updateBusinessDate(any());
        verify(synapse).postBusinessDate(NEXT, D);
    }

    @Test
    void refusesWhenFineractIsOnSomeOtherDay() {
        fineractIsOn(D.minusDays(1));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodRolloverStep.CODE_PREFLIGHT, thrown.getCode());
        verify(businessDateWrite, never()).updateBusinessDate(any());
        verify(runService).failed(any(), eq(EodRolloverStep.CODE_PREFLIGHT), anyString());
    }

    @Test
    void refusesWhenTheBusinessDateFeatureIsOff() {
        when(configuration.isBusinessDateEnabled()).thenReturn(false);

        assertEquals(EodRolloverStep.CODE_PREFLIGHT, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    void aFineractRefusalFailsTheStep() {
        doThrow(new BusinessDateActionException("business.date.is.not.enabled", "closed")).when(businessDateWrite)
                .updateBusinessDate(eq(moveTo(NEXT)));

        assertEquals(EodRolloverStep.CODE_ROLLOVER, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
        verify(synapse, never()).postBusinessDate(any(), any());
    }

    @Test
    void aCobDateThatWillNotFollowIsAnExceptionRow_notAFailure() {
        when(configuration.isCOBDateAdjustmentEnabled()).thenReturn(false);

        underTest.execute(run);

        verify(runService).recordException(eq(run), eq(EodRolloverStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodRolloverStep.CODE_COB_DATE_NOT_ADJUSTED), anyString(), any());
        verify(runService).completed(any(), any());
    }

    @Test
    void aRefusedPushFailsTheStepAfterFineractAdvanced() {
        when(synapse.postBusinessDate(NEXT, D)).thenThrow(new SynapsePostingException("HTTP 409 ahead of Fineract", null));

        assertEquals(EodRolloverStep.CODE_SYNAPSE_PUSH, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutSynapseTheChainCannotRun() {
        ObjectProvider<SynapseTransactionClient> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        underTest = new EodRolloverStep(runService, EodStepTestSupport.fastProperties(), configuration, businessDateRead, businessDateWrite,
                absent, nextBusinessDate, calendar);

        assertEquals(AbstractEodStep.CODE_SYNAPSE_DISABLED,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    private void rollsTo(LocalDate closing, LocalDate next) {
        when(nextBusinessDate.after(closing)).thenReturn(next);
        when(calendar.findPeriodCovering(next)).thenReturn(Optional.of(FinancialPeriod.create(FinancialYear.create("FY" + next.getYear()),
                String.format("M%02d", next.getMonthValue()), next.withDayOfMonth(1), next.withDayOfMonth(next.lengthOfMonth()))));
        SynapseBusinessDateResponse pushed = new SynapseBusinessDateResponse();
        pushed.setBusinessDate(next);
        when(synapse.postBusinessDate(next, closing)).thenReturn(pushed);
    }

    private static BusinessDateDTO moveTo(LocalDate date) {
        return BusinessDateDTO.builder().type(BusinessDateType.BUSINESS_DATE).description(BusinessDateType.BUSINESS_DATE.getDescription())
                .date(date).build();
    }

    private void fineractIsOn(LocalDate businessDate) {
        when(businessDateRead.getBusinessDates())
                .thenReturn(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, businessDate, BusinessDateType.COB_DATE, businessDate.minusDays(1))));
    }
}
