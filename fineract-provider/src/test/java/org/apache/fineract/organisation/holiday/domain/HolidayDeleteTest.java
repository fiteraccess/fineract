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
package org.apache.fineract.organisation.holiday.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

/** The holiday name is unique across every row ever stored, deleted ones included. */
class HolidayDeleteTest {

    private static final LocalDate CHRISTMAS = LocalDate.of(2026, 12, 25);

    @Test
    void aDeletedHolidayGivesUpItsName_soTheSameDayCanBeAddedBack() {
        Holiday holiday = active(41L, "Christmas Day 2026-12-25");

        holiday.delete();

        assertEquals(HolidayStatusType.DELETED.getValue(), holiday.getStatus());
        assertEquals("Christmas Day 2026-12-25#41", holiday.getName());
    }

    @Test
    void aNameAtTheColumnsLengthIsCutToMakeRoomForTheSuffix() {
        Holiday holiday = active(1234L, "N".repeat(100));

        holiday.delete();

        assertEquals("N".repeat(95) + "#1234", holiday.getName());
    }

    @Test
    void deletingTwiceIsRefused_andTheNameIsNotSuffixedAgain() {
        Holiday holiday = active(41L, "Christmas Day 2026-12-25");
        holiday.delete();

        assertThrows(PlatformApiDataValidationException.class, holiday::delete);

        assertEquals("Christmas Day 2026-12-25#41", holiday.getName());
    }

    private static Holiday active(long id, String name) {
        Holiday holiday = new Holiday().setName(name).setFromDate(CHRISTMAS).setToDate(CHRISTMAS)
                .setStatus(HolidayStatusType.ACTIVE.getValue());
        holiday.setId(id);
        return holiday;
    }
}
