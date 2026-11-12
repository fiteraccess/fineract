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
package org.apache.fineract.organisation.financialcalendar.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.financialcalendar.data.FinancialYearPlan;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FinancialYearValidatorTest {

    private final FinancialYearValidator underTest = new FinancialYearValidator(new FromJsonHelper());

    @Test
    void acceptsTwelveCalendarMonths_andReturnsThemInDateOrder() {
        FinancialYearPlan plan = underTest.validateForUpsert("FY2027", body(2027, months(2027), true));

        assertEquals(12, plan.periods().size());
        assertEquals("M01", plan.periods().get(0).periodCode());
        assertEquals(LocalDate.of(2027, 12, 31), plan.periods().get(11).endDate());
        assertEquals(true, plan.holidaysConfirmed());
    }

    @Test
    void aLeapYearFebruaryEndsOnThe29th() {
        FinancialYearPlan plan = underTest.validateForUpsert("FY2028", body(2028, months(2028), false));

        assertEquals(LocalDate.of(2028, 2, 29), plan.periods().get(1).endDate());
        assertFalse(plan.holidaysConfirmed());
    }

    @Nested
    class Tiling {

        @Test
        void aGapNamesTheBrokenBoundary() {
            List<String[]> periods = months(2026);
            periods.set(3, new String[] { "M04", "2026-04-02", "2026-04-30" });

            assertEquals(List.of("M03 ends 31-03-2026 but M04 starts 02-04-2026"), messages("FY2026", body(2026, periods, true)));
        }

        @Test
        void anOverlapNamesTheBrokenBoundary() {
            List<String[]> periods = months(2026);
            periods.set(3, new String[] { "M04", "2026-03-31", "2026-04-30" });

            assertEquals(List.of("M03 ends 31-03-2026 but M04 starts 31-03-2026"), messages("FY2026", body(2026, periods, true)));
        }

        @Test
        void periodsMustStartAndEndWithTheYear() {
            List<String[]> periods = months(2026);
            periods.set(0, new String[] { "M01", "2026-01-02", "2026-01-31" });
            periods.set(11, new String[] { "M12", "2026-12-01", "2026-12-30" });

            assertEquals(List.of("The financial year starts 01-01-2026 but M01 starts 02-01-2026",
                    "M12 ends 30-12-2026 but the financial year ends 31-12-2026"), messages("FY2026", body(2026, periods, true)));
        }
    }

    @Test
    void aRepeatedPeriodCodeIsRejected() {
        List<String[]> periods = months(2026);
        periods.set(1, new String[] { "M01", "2026-02-01", "2026-02-28" });

        assertEquals(List.of("Period code M01 is used more than once"), messages("FY2026", body(2026, periods, true)));
    }

    @Test
    void datesMustBeIso() {
        String json = "{\"startDate\":\"01/01/2026\",\"endDate\":\"2026-12-31\",\"periods\":[{\"periodCode\":\"M01\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\"}]}";

        assertEquals(List.of("startDate must be an ISO date (yyyy-MM-dd), was 01/01/2026"), messages("FY2026", json));
    }

    @Test
    void periodsAreRequired() {
        assertEquals(List.of("At least one period is required"),
                messages("FY2026", "{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"periods\":[]}"));
    }

    private List<String> messages(String cycle, String json) {
        PlatformApiDataValidationException thrown = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateForUpsert(cycle, json));
        return thrown.getErrors().stream().map(ApiParameterError::getDefaultUserMessage).toList();
    }

    private static List<String[]> months(int year) {
        List<String[]> periods = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(year, month, 1);
            periods.add(new String[] { String.format("M%02d", month), start.toString(),
                    start.withDayOfMonth(start.lengthOfMonth()).toString() });
        }
        return periods;
    }

    private static String body(int year, List<String[]> periods, boolean confirmed) {
        StringBuilder json = new StringBuilder("{\"description\":\"FISCAL YEAR " + year + "\",\"startDate\":\"" + year
                + "-01-01\",\"endDate\":\"" + year + "-12-31\",\"holidaysConfirmed\":" + confirmed + ",\"periods\":[");
        for (int i = 0; i < periods.size(); i++) {
            String[] p = periods.get(i);
            json.append(i == 0 ? "" : ",").append("{\"periodCode\":\"").append(p[0]).append("\",\"startDate\":\"").append(p[1])
                    .append("\",\"endDate\":\"").append(p[2]).append("\"}");
        }
        return json.append("]}").toString();
    }
}
