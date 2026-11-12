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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.financialcalendar.data.FinancialYearData;
import org.apache.fineract.organisation.financialcalendar.data.FinancialYearPlan;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialPeriod;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialPeriodRepository;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYear;
import org.apache.fineract.organisation.financialcalendar.domain.FinancialYearRepository;
import org.apache.fineract.organisation.financialcalendar.exception.FinancialYearNotFoundException;
import org.apache.fineract.organisation.financialcalendar.serialization.FinancialYearValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Financial years and their periods: maintenance through the API, and the reads EOD uses to roll the business date. */
@Service
@RequiredArgsConstructor
public class FinancialCalendarService {

    public static final int PERIODS_PER_YEAR = 12;

    private final FinancialYearRepository yearRepository;
    private final FinancialPeriodRepository periodRepository;
    private final FinancialYearValidator validator;

    @Transactional(readOnly = true)
    public List<FinancialYearData> retrieveAll() {
        return yearRepository.findAllByOrderByStartDateAsc().stream().map(this::toData).toList();
    }

    @Transactional(readOnly = true)
    public FinancialYearData retrieve(String financialCycle) {
        return toData(requireYear(financialCycle));
    }

    @Transactional(readOnly = true)
    public Optional<FinancialPeriod> findPeriodCovering(LocalDate date) {
        return periodRepository.findCovering(date);
    }

    @Transactional(readOnly = true)
    public Optional<FinancialYear> findYearCovering(LocalDate date) {
        return yearRepository.findCovering(date);
    }

    /**
     * Marks {@code year} closed by the EOD run that left it, dated that run's closing business date. The year-end step
     * checks the year is still open before calling this.
     */
    @Transactional
    public void closeYear(FinancialYear year, LocalDate closedOn) {
        year.close(closedOn);
        yearRepository.saveAndFlush(year);
    }

    /**
     * What stops EOD from rolling into {@code year}: its financial year missing, fewer than twelve periods (naming the
     * absent M01..M12 codes), or its holidays not confirmed. Empty when the year is ready.
     */
    @Transactional(readOnly = true)
    public List<String> findYearReadinessProblems(int year) {
        Optional<FinancialYear> found = yearRepository.findCovering(LocalDate.of(year, 1, 1));
        if (found.isEmpty()) {
            return List.of("no financial year covers " + year);
        }
        FinancialYear financialYear = found.get();
        List<String> problems = new ArrayList<>();
        List<FinancialPeriod> periods = periodRepository.findByFinancialYearOrderByStartDateAsc(financialYear);
        if (periods.size() != PERIODS_PER_YEAR) {
            List<String> present = periods.stream().map(FinancialPeriod::getPeriodCode).toList();
            List<String> missing = IntStream.rangeClosed(1, PERIODS_PER_YEAR).mapToObj(month -> String.format("M%02d", month))
                    .filter(code -> !present.contains(code)).toList();
            problems.add(financialYear.getFinancialCycle() + " has " + periods.size() + " of " + PERIODS_PER_YEAR + " periods"
                    + (missing.isEmpty() ? "" : " (missing " + String.join(", ", missing) + ")"));
        }
        if (!financialYear.isHolidaysConfirmed()) {
            problems.add(financialYear.getFinancialCycle() + " holidays are not confirmed");
        }
        return problems;
    }

    @Transactional
    public CommandProcessingResult upsert(JsonCommand command) {
        String financialCycle = command.getTransactionId();
        FinancialYearPlan plan = validator.validateForUpsert(financialCycle, command.json());
        rejectOverlap(plan);
        FinancialYear year = yearRepository.findByFinancialCycle(financialCycle).orElseGet(() -> FinancialYear.create(financialCycle));
        rejectIfClosed(year);
        year.replace(plan.description(), plan.startDate(), plan.endDate(), plan.holidaysConfirmed());
        yearRepository.saveAndFlush(year);

        Map<String, FinancialPeriod> existing = periodRepository.findByFinancialYearOrderByStartDateAsc(year).stream()
                .collect(Collectors.toMap(FinancialPeriod::getPeriodCode, Function.identity()));
        List<String> kept = plan.periods().stream().map(FinancialYearPlan.Period::periodCode).toList();
        List<FinancialPeriod> removed = existing.values().stream().filter(period -> !kept.contains(period.getPeriodCode())).toList();
        removed.forEach(this::rejectIfPosted);
        periodRepository.deleteAll(removed);
        periodRepository.flush();
        for (FinancialYearPlan.Period period : plan.periods()) {
            FinancialPeriod current = existing.get(period.periodCode());
            if (current == null) {
                periodRepository.save(FinancialPeriod.create(year, period.periodCode(), period.startDate(), period.endDate()));
            } else {
                current.moveTo(period.startDate(), period.endDate());
            }
        }
        periodRepository.flush();
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(year.getId())
                .withResourceIdAsString(financialCycle).build();
    }

    @Transactional
    public CommandProcessingResult delete(JsonCommand command) {
        String financialCycle = command.getTransactionId();
        FinancialYear year = requireYear(financialCycle);
        rejectIfClosed(year);
        List<FinancialPeriod> periods = periodRepository.findByFinancialYearOrderByStartDateAsc(year);
        periods.forEach(this::rejectIfPosted);
        periodRepository.deleteAll(periods);
        periodRepository.flush();
        yearRepository.delete(year);
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(year.getId())
                .withResourceIdAsString(financialCycle).build();
    }

    private void rejectOverlap(FinancialYearPlan plan) {
        List<FinancialYear> others = yearRepository.findOthersOverlapping(plan.financialCycle(), plan.startDate(), plan.endDate());
        if (!others.isEmpty()) {
            FinancialYear other = others.get(0);
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.parameterError(
                    "validation.msg.financialyear.overlaps", plan.financialCycle() + " overlaps " + other.getFinancialCycle() + " ("
                            + other.getStartDate() + " to " + other.getEndDate() + "); only one financial cycle per year",
                    "startDate", other.getFinancialCycle())));
        }
    }

    private void rejectIfClosed(FinancialYear year) {
        if (year.isClosed()) {
            throw new GeneralPlatformDomainRuleException("error.msg.financialyear.closed",
                    year.getFinancialCycle() + " was closed by the EOD of " + year.getClosedOn() + " and cannot be changed",
                    year.getFinancialCycle(), year.getClosedOn());
        }
    }

    private void rejectIfPosted(FinancialPeriod period) {
        long posted = periodRepository.countPostedTransactionsBetween(period.getStartDate(), period.getEndDate());
        if (posted > 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.financialyear.period.has.transactions", period.getPeriodCode() + " of "
                    + period.getFinancialYear().getFinancialCycle() + " has " + posted + " posted transactions and cannot be deleted",
                    period.getPeriodCode(), posted);
        }
    }

    private FinancialYear requireYear(String financialCycle) {
        return yearRepository.findByFinancialCycle(financialCycle).orElseThrow(() -> new FinancialYearNotFoundException(financialCycle));
    }

    private FinancialYearData toData(FinancialYear year) {
        List<FinancialYearData.FinancialPeriodData> periods = periodRepository.findByFinancialYearOrderByStartDateAsc(year).stream()
                .map(period -> new FinancialYearData.FinancialPeriodData(period.getPeriodCode(), period.getStartDate().toString(),
                        period.getEndDate().toString()))
                .toList();
        return new FinancialYearData(year.getFinancialCycle(), year.getDescription(), year.getStartDate().toString(),
                year.getEndDate().toString(), year.isHolidaysConfirmed(), year.isClosed() ? year.getClosedOn().toString() : null, periods);
    }
}
