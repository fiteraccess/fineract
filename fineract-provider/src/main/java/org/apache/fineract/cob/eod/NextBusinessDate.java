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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.holiday.service.HolidayUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.springframework.stereotype.Component;

/**
 * The business date EOD rolls to: the first calendar date after the closing day that no active head-office holiday
 * covers. Weekends are working days; working-day configuration plays no part.
 */
@Component
@RequiredArgsConstructor
public class NextBusinessDate {

    public static final String CODE_NO_BUSINESS_DATE = "EOD-NO-BUSINESS-DATE";
    static final int MAX_GAP_DAYS = 31;

    private final HolidayRepositoryWrapper holidayRepository;
    private final OfficeRepository officeRepository;

    public LocalDate after(LocalDate closing) {
        Office headOffice = officeRepository.findByParentIsNull()
                .orElseThrow(() -> new EodStepFailedException(CODE_NO_BUSINESS_DATE, "no head office to read holidays for"));
        List<Holiday> holidays = holidayRepository.findByOfficeIdAndGreaterThanDate(headOffice.getId(), closing.plusDays(1));
        return firstWorkingDayAfter(closing, holidays).orElseThrow(() -> new EodStepFailedException(CODE_NO_BUSINESS_DATE,
                "every date from " + closing.plusDays(1) + " for " + MAX_GAP_DAYS + " days is a holiday"));
    }

    static Optional<LocalDate> firstWorkingDayAfter(LocalDate closing, List<Holiday> holidays) {
        for (LocalDate day = closing.plusDays(1); !day.isAfter(closing.plusDays(MAX_GAP_DAYS)); day = day.plusDays(1)) {
            if (!HolidayUtil.isHoliday(day, holidays)) {
                return Optional.of(day);
            }
        }
        return Optional.empty();
    }
}
