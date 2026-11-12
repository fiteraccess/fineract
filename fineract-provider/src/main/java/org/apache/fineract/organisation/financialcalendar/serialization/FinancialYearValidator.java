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

import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.DESCRIPTION;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.END_DATE;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.HOLIDAYS_CONFIRMED;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.PERIODS;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.PERIOD_CODE;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.PERIOD_PARAMETERS;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.START_DATE;
import static org.apache.fineract.organisation.financialcalendar.api.FinancialCalendarApiConstants.UPSERT_PARAMETERS;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.financialcalendar.data.FinancialYearPlan;
import org.springframework.stereotype.Component;

/**
 * Turns an upsert body into a {@link FinancialYearPlan}: required fields present, ISO dates, unique period codes, and
 * periods that tile the year exactly. Checks that need the database (overlapping years, periods with postings) are the
 * service's.
 */
@Component
@RequiredArgsConstructor
public class FinancialYearValidator {

    static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final FromJsonHelper fromJsonHelper;

    public FinancialYearPlan validateForUpsert(String financialCycle, String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }
        fromJsonHelper.checkForUnsupportedParameters(MAP_TYPE, json, UPSERT_PARAMETERS);
        JsonObject body = fromJsonHelper.parse(json).getAsJsonObject();
        List<ApiParameterError> errors = new ArrayList<>();
        if (StringUtils.isBlank(financialCycle) || financialCycle.length() > 20) {
            errors.add(ApiParameterError.parameterError("validation.msg.financialyear.financialCycle.invalid",
                    "Financial cycle is required and at most 20 characters", "financialCycle", financialCycle));
        }
        String description = fromJsonHelper.extractStringNamed(DESCRIPTION, body);
        if (description != null && description.length() > 100) {
            errors.add(ApiParameterError.parameterError("validation.msg.financialyear.description.too.long",
                    "Description is at most 100 characters", DESCRIPTION));
        }
        LocalDate start = date(body, START_DATE, "", errors);
        LocalDate end = date(body, END_DATE, "", errors);
        if (start != null && end != null && !start.isBefore(end)) {
            errors.add(ApiParameterError.parameterError("validation.msg.financialyear.end.not.after.start",
                    "End date " + SHOWN.format(end) + " must be after start date " + SHOWN.format(start), END_DATE));
        }
        Boolean confirmed = fromJsonHelper.extractBooleanNamed(HOLIDAYS_CONFIRMED, body);
        List<FinancialYearPlan.Period> periods = periods(body, errors);
        if (errors.isEmpty()) {
            errors.addAll(tilingProblems(start, end, periods));
        }
        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
        return new FinancialYearPlan(financialCycle, StringUtils.trimToNull(description), start, end, Boolean.TRUE.equals(confirmed),
                periods.stream().sorted(Comparator.comparing(FinancialYearPlan.Period::startDate)).toList());
    }

    /** Every way the periods fail to cover [start, end] exactly once, each naming the broken boundary. */
    static List<ApiParameterError> tilingProblems(LocalDate start, LocalDate end, List<FinancialYearPlan.Period> periods) {
        List<ApiParameterError> errors = new ArrayList<>();
        List<FinancialYearPlan.Period> ordered = periods.stream().sorted(Comparator.comparing(FinancialYearPlan.Period::startDate))
                .toList();
        FinancialYearPlan.Period first = ordered.get(0);
        if (!first.startDate().equals(start)) {
            errors.add(boundary("The financial year starts " + SHOWN.format(start) + " but " + first.periodCode() + " starts "
                    + SHOWN.format(first.startDate())));
        }
        for (int i = 1; i < ordered.size(); i++) {
            FinancialYearPlan.Period previous = ordered.get(i - 1);
            FinancialYearPlan.Period next = ordered.get(i);
            if (!previous.endDate().plusDays(1).equals(next.startDate())) {
                errors.add(boundary(previous.periodCode() + " ends " + SHOWN.format(previous.endDate()) + " but " + next.periodCode()
                        + " starts " + SHOWN.format(next.startDate())));
            }
        }
        FinancialYearPlan.Period last = ordered.get(ordered.size() - 1);
        if (!last.endDate().equals(end)) {
            errors.add(boundary(
                    last.periodCode() + " ends " + SHOWN.format(last.endDate()) + " but the financial year ends " + SHOWN.format(end)));
        }
        return errors;
    }

    private List<FinancialYearPlan.Period> periods(JsonObject body, List<ApiParameterError> errors) {
        JsonElement element = body.get(PERIODS);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            errors.add(ApiParameterError.parameterError("validation.msg.financialyear.periods.required", "At least one period is required",
                    PERIODS));
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<FinancialYearPlan.Period> periods = new ArrayList<>(array.size());
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            fromJsonHelper.checkForUnsupportedParameters(MAP_TYPE, array.get(i).toString(), PERIOD_PARAMETERS);
            JsonObject item = array.get(i).getAsJsonObject();
            String prefix = PERIODS + "[" + i + "].";
            String code = StringUtils.trimToNull(fromJsonHelper.extractStringNamed(PERIOD_CODE, item));
            if (code == null || code.length() > 10) {
                errors.add(ApiParameterError.parameterError("validation.msg.financialyear.periodCode.invalid",
                        "Period code is required and at most 10 characters", prefix + PERIOD_CODE));
            } else if (!codes.add(code)) {
                errors.add(ApiParameterError.parameterError("validation.msg.financialyear.periodCode.duplicate",
                        "Period code " + code + " is used more than once", prefix + PERIOD_CODE, code));
            }
            LocalDate start = date(item, START_DATE, prefix, errors);
            LocalDate end = date(item, END_DATE, prefix, errors);
            if (start != null && end != null && end.isBefore(start)) {
                errors.add(ApiParameterError.parameterError("validation.msg.financialyear.period.end.before.start",
                        code + " ends " + SHOWN.format(end) + " before it starts " + SHOWN.format(start), prefix + END_DATE));
            }
            periods.add(new FinancialYearPlan.Period(code, start, end));
        }
        return periods;
    }

    private LocalDate date(JsonObject body, String name, String prefix, List<ApiParameterError> errors) {
        String value = StringUtils.trimToNull(fromJsonHelper.extractStringNamed(name, body));
        if (value == null) {
            errors.add(ApiParameterError.parameterError("validation.msg.financialyear." + name + ".required", name + " is required",
                    prefix + name));
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            errors.add(ApiParameterError.parameterError("validation.msg.financialyear." + name + ".invalid",
                    name + " must be an ISO date (yyyy-MM-dd), was " + value, prefix + name, value));
            return null;
        }
    }

    private static ApiParameterError boundary(String message) {
        return ApiParameterError.parameterError("validation.msg.financialyear.periods.not.contiguous", message, PERIODS);
    }
}
