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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYear;
import org.apache.fineract.organisation.financialcalendar.service.FinancialCalendarService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.springframework.stereotype.Component;

/**
 * The year-end step: once the roll has left the closing day's financial year, confirms that month end closed every
 * office through the year's last day and marks the year closed, so its record can no longer change. Every other night
 * it records that the year did not change. The next year's readiness was checked by the rollover before the date moved.
 * The EOD run on a year's last business day is the year-end run; there is no separate job.
 */
@Component
public class EodYearEndStep extends AbstractEodStep {

    public static final String NAME = "EOD_YEAR_END";
    public static final String CODE_YEAR_NOT_CLOSED = "EOD-YEAR-NOT-CLOSED";

    private final FinancialCalendarService financialCalendar;
    private final GLClosureRepository closures;
    private final OfficeRepository officeRepository;

    public EodYearEndStep(EodRunService runService, FineractProperties properties, FinancialCalendarService financialCalendar,
            GLClosureRepository closures, OfficeRepository officeRepository) {
        super(runService, properties);
        this.financialCalendar = financialCalendar;
        this.closures = closures;
        this.officeRepository = officeRepository;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        requirePrerequisite(run, EodMonthEndStep.NAME);
        LocalDate closing = run.getBusinessDate();
        Optional<FinancialYear> found = financialCalendar.findYearCovering(closing);
        if (found.isEmpty()) {
            return detail("yearChanged", false, "reason", "no financial year covers " + closing);
        }
        FinancialYear year = found.get();
        if (!run.rolledTo().isAfter(year.getEndDate())) {
            return detail("yearChanged", false, "financialCycle", year.getFinancialCycle());
        }
        List<Office> offices = officeRepository.findAll();
        List<String> notClosed = officesNotClosedThrough(offices, year.getEndDate());
        if (!notClosed.isEmpty()) {
            throw new EodStepFailedException(CODE_YEAR_NOT_CLOSED,
                    year.getFinancialCycle() + " ends " + year.getEndDate() + " but these offices are not closed through it: " + notClosed
                            + "; " + EodMonthEndStep.NAME + " should have closed them");
        }
        boolean alreadyClosed = year.isClosed();
        LocalDate closedOn = alreadyClosed ? year.getClosedOn() : closing;
        if (!alreadyClosed) {
            financialCalendar.closeYear(year, closing);
        }
        String nextCycle = financialCalendar.findYearCovering(run.rolledTo()).map(FinancialYear::getFinancialCycle).orElse(null);
        return detail("yearChanged", true, "financialCycle", year.getFinancialCycle(), "startDate", year.getStartDate().toString(),
                "endDate", year.getEndDate().toString(), "officesClosedThrough", offices.size(), "closedOn", closedOn.toString(),
                "alreadyClosed", alreadyClosed, "nextFinancialCycle", nextCycle);
    }

    private List<String> officesNotClosedThrough(List<Office> offices, LocalDate date) {
        List<String> notClosed = new ArrayList<>();
        for (Office office : offices) {
            GLClosure latest = closures.getLatestGLClosureByBranch(office.getId());
            if (latest == null || latest.getClosingDate().isBefore(date)) {
                notClosed.add(office.getName());
            }
        }
        return notClosed;
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD year end: confirm every office is closed through a financial year the business date has left and mark it closed";
    }
}
