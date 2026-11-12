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
package org.apache.fineract.organisation.financialcalendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialPeriod;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialPeriodRepository;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYear;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYearRepository;
import org.apache.fineract.organisation.financialcalendar.serialization.FinancialYearValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FinancialCalendarServiceTest {

    private final FinancialYearRepository years = mock(FinancialYearRepository.class);
    private final FinancialPeriodRepository periods = mock(FinancialPeriodRepository.class);
    private final FromJsonHelper json = new FromJsonHelper();
    private final FinancialCalendarService underTest = new FinancialCalendarService(years, periods, new FinancialYearValidator(json));

    @Nested
    class YearReadiness {

        @Test
        void aYearWithTwelvePeriodsAndConfirmedHolidaysIsReady() {
            FinancialYear fy2027 = year("FY2027", 2027, true);
            when(years.findCovering(LocalDate.of(2027, 1, 1))).thenReturn(Optional.of(fy2027));
            when(periods.findByFinancialYearOrderByStartDateAsc(fy2027)).thenReturn(monthsOf(fy2027, 2027));

            assertEquals(List.of(), underTest.findYearReadinessProblems(2027));
        }

        @Test
        void aMissingYearIsNamed() {
            when(years.findCovering(LocalDate.of(2027, 1, 1))).thenReturn(Optional.empty());

            assertEquals(List.of("no financial year covers 2027"), underTest.findYearReadinessProblems(2027));
        }

        @Test
        void missingPeriodsAndUnconfirmedHolidaysAreBothNamed() {
            FinancialYear fy2027 = year("FY2027", 2027, false);
            List<FinancialPeriod> elevenMonths = new ArrayList<>(monthsOf(fy2027, 2027));
            elevenMonths.remove(6);
            when(years.findCovering(LocalDate.of(2027, 1, 1))).thenReturn(Optional.of(fy2027));
            when(periods.findByFinancialYearOrderByStartDateAsc(fy2027)).thenReturn(elevenMonths);

            assertEquals(List.of("FY2027 has 11 of 12 periods (missing M07)", "FY2027 holidays are not confirmed"),
                    underTest.findYearReadinessProblems(2027));
        }
    }

    @Nested
    class Upsert {

        @Test
        void aSecondCycleForTheSameYearIsRejected() {
            FinancialYear fy2026 = year("FY2026", 2026, true);
            when(years.findOthersOverlapping("FY2026B", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).thenReturn(List.of(fy2026));

            PlatformApiDataValidationException thrown = assertThrows(PlatformApiDataValidationException.class,
                    () -> underTest.upsert(command("FY2026B", oneYearBody(2026))));

            assertTrue(thrown.getErrors().get(0).getDefaultUserMessage().contains("overlaps FY2026"));
            verify(years, never()).saveAndFlush(any());
        }

        @Test
        void removingAPeriodWithPostingsIsRefusedWithTheCount() {
            FinancialYear fy2026 = year("FY2026", 2026, true);
            List<FinancialPeriod> twelve = monthsOf(fy2026, 2026);
            when(years.findByFinancialCycle("FY2026")).thenReturn(Optional.of(fy2026));
            when(periods.findByFinancialYearOrderByStartDateAsc(fy2026)).thenReturn(twelve);
            when(periods.countPostedTransactionsBetween(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))).thenReturn(42L);

            GeneralPlatformDomainRuleException thrown = assertThrows(GeneralPlatformDomainRuleException.class,
                    () -> underTest.upsert(command("FY2026", oneYearBody(2026))));

            assertEquals("M02 of FY2026 has 42 posted transactions and cannot be deleted", thrown.getDefaultUserMessage());
            verify(periods, never()).deleteAll(anyList());
        }
    }

    @Test
    void deletingAYearWithPostingsIsRefused() {
        FinancialYear fy2026 = year("FY2026", 2026, true);
        when(years.findByFinancialCycle("FY2026")).thenReturn(Optional.of(fy2026));
        when(periods.findByFinancialYearOrderByStartDateAsc(fy2026)).thenReturn(monthsOf(fy2026, 2026));
        when(periods.countPostedTransactionsBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))).thenReturn(3L);

        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.delete(command("FY2026", "{}")));

        verify(years, never()).delete(eq(fy2026));
    }

    @Nested
    class ClosedYear {

        private final FinancialYear fy2026 = year("FY2026", 2026, true);

        @BeforeEach
        void closedByTheLastEodOfTheYear() {
            fy2026.close(LocalDate.of(2026, 12, 30));
            when(years.findByFinancialCycle("FY2026")).thenReturn(Optional.of(fy2026));
        }

        @Test
        void cannotBeChanged_namingTheClosingEod() {
            GeneralPlatformDomainRuleException thrown = assertThrows(GeneralPlatformDomainRuleException.class,
                    () -> underTest.upsert(command("FY2026", oneYearBody(2026))));

            assertEquals("FY2026 was closed by the EOD of 2026-12-30 and cannot be changed", thrown.getDefaultUserMessage());
            verify(years, never()).saveAndFlush(any());
        }

        @Test
        void cannotBeDeleted() {
            assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.delete(command("FY2026", "{}")));

            verify(years, never()).delete(eq(fy2026));
            verify(periods, never()).deleteAll(anyList());
        }

        @Test
        void showsItsClosingDate() {
            assertEquals("2026-12-30", underTest.retrieve("FY2026").closedOn());
        }
    }

    @Test
    void anOpenYearShowsNoClosingDate() {
        when(years.findByFinancialCycle("FY2026")).thenReturn(Optional.of(year("FY2026", 2026, true)));

        assertNull(underTest.retrieve("FY2026").closedOn());
    }

    @Test
    void closingAYearStampsTheClosingBusinessDate() {
        FinancialYear fy2026 = year("FY2026", 2026, true);

        underTest.closeYear(fy2026, LocalDate.of(2026, 12, 30));

        assertEquals(LocalDate.of(2026, 12, 30), fy2026.getClosedOn());
        verify(years).saveAndFlush(fy2026);
    }

    private JsonCommand command(String cycle, String body) {
        return JsonCommand.from(body, json.parse(body), json, "FINANCIALYEAR", null, null, null, null, null, null, cycle, null, null, null,
                null, null, null);
    }

    /** One period, M01, covering the whole year: every other existing period is removed by it. */
    private static String oneYearBody(int year) {
        return "{\"startDate\":\"" + year + "-01-01\",\"endDate\":\"" + year
                + "-12-31\",\"periods\":[{\"periodCode\":\"M01\",\"startDate\":\"" + year + "-01-01\",\"endDate\":\"" + year
                + "-12-31\"}]}";
    }

    private static FinancialYear year(String cycle, int year, boolean confirmed) {
        FinancialYear financialYear = FinancialYear.create(cycle);
        financialYear.replace(null, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), confirmed);
        return financialYear;
    }

    private static List<FinancialPeriod> monthsOf(FinancialYear financialYear, int year) {
        List<FinancialPeriod> months = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(year, month, 1);
            months.add(FinancialPeriod.create(financialYear, String.format("M%02d", month), start,
                    start.withDayOfMonth(start.lengthOfMonth())));
        }
        return months;
    }
}
