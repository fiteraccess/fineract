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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NextBusinessDateTest {

    private static final LocalDate CHRISTMAS_EVE = LocalDate.of(2026, 12, 24);

    @Nested
    class FirstWorkingDayAfter {

        @Test
        void withNoHolidaysItIsTheNextCalendarDay() {
            assertEquals(Optional.of(CHRISTMAS_EVE.plusDays(1)), NextBusinessDate.firstWorkingDayAfter(CHRISTMAS_EVE, List.of()));
        }

        @Test
        void consecutiveHolidaysAreAllSkipped() {
            List<Holiday> holidays = List.of(holiday("2026-12-25", "2026-12-25"), holiday("2026-12-26", "2026-12-27"));

            assertEquals(Optional.of(LocalDate.of(2026, 12, 28)), NextBusinessDate.firstWorkingDayAfter(CHRISTMAS_EVE, holidays));
        }

        @Test
        void weekendsAreWorkingDays() {
            LocalDate friday = LocalDate.of(2026, 10, 30);

            assertEquals(Optional.of(LocalDate.of(2026, 10, 31)), NextBusinessDate.firstWorkingDayAfter(friday, List.of()));
        }

        @Test
        void aNewYearsDayHolidayRollsToTheSecond() {
            LocalDate newYearsEve = LocalDate.of(2026, 12, 31);

            assertEquals(Optional.of(LocalDate.of(2027, 1, 2)),
                    NextBusinessDate.firstWorkingDayAfter(newYearsEve, List.of(holiday("2027-01-01", "2027-01-01"))));
        }

        @Test
        void aHolidayBeforeTheClosingDayIsIgnored() {
            assertEquals(Optional.of(CHRISTMAS_EVE.plusDays(1)),
                    NextBusinessDate.firstWorkingDayAfter(CHRISTMAS_EVE, List.of(holiday("2026-12-20", "2026-12-24"))));
        }

        @Test
        void aGapLongerThanTheBoundFindsNothing() {
            assertEquals(Optional.empty(), NextBusinessDate.firstWorkingDayAfter(CHRISTMAS_EVE,
                    List.of(holiday("2026-12-25", CHRISTMAS_EVE.plusDays(NextBusinessDate.MAX_GAP_DAYS).toString()))));
        }
    }

    @Nested
    class After {

        private final HolidayRepositoryWrapper holidays = mock(HolidayRepositoryWrapper.class);
        private final OfficeRepository offices = mock(OfficeRepository.class);
        private final Office headOffice = mock(Office.class);
        private final NextBusinessDate underTest = new NextBusinessDate(holidays, offices);

        @Test
        void readsTheHeadOfficeHolidaysFromTheDayAfterClosing() {
            when(headOffice.getId()).thenReturn(1L);
            when(offices.findByParentIsNull()).thenReturn(Optional.of(headOffice));
            when(holidays.findByOfficeIdAndGreaterThanDate(1L, CHRISTMAS_EVE.plusDays(1)))
                    .thenReturn(List.of(holiday("2026-12-25", "2026-12-27")));

            assertEquals(LocalDate.of(2026, 12, 28), underTest.after(CHRISTMAS_EVE));
        }

        @Test
        void anUnboundedGapFailsTheStep() {
            when(headOffice.getId()).thenReturn(1L);
            when(offices.findByParentIsNull()).thenReturn(Optional.of(headOffice));
            when(holidays.findByOfficeIdAndGreaterThanDate(1L, CHRISTMAS_EVE.plusDays(1)))
                    .thenReturn(List.of(holiday("2026-12-25", "2027-12-31")));

            assertEquals(NextBusinessDate.CODE_NO_BUSINESS_DATE,
                    assertThrows(EodStepFailedException.class, () -> underTest.after(CHRISTMAS_EVE)).getCode());
        }

        @Test
        void withoutAHeadOfficeTheStepFails() {
            when(offices.findByParentIsNull()).thenReturn(Optional.empty());

            assertEquals(NextBusinessDate.CODE_NO_BUSINESS_DATE,
                    assertThrows(EodStepFailedException.class, () -> underTest.after(CHRISTMAS_EVE)).getCode());
        }
    }

    private static Holiday holiday(String from, String to) {
        return new Holiday().setFromDate(LocalDate.parse(from)).setToDate(LocalDate.parse(to));
    }
}
