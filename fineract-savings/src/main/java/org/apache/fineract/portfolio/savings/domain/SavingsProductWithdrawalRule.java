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
package org.apache.fineract.portfolio.savings.domain;

import static org.apache.fineract.portfolio.savings.SavingsApiConstants.monthlyWithdrawalThresholdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.qualifyingWithdrawalTypesParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.withdrawalCounterEnabledParamName;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.savings.QualifyingWithdrawalType;
import org.apache.fineract.portfolio.savings.data.SavingsProductWithdrawalRuleData;

/**
 * AB-566: a Regular Savings product's monthly withdrawal rule. Synapse counts the qualifying withdrawals of each
 * calendar month; above the threshold that month's interest is forfeited. A rule is stored per product from the first
 * day of the month it takes effect; a product without one runs on the defaults.
 */
public record SavingsProductWithdrawalRule(LocalDate effectiveFrom, boolean withdrawalCounterEnabled, int monthlyWithdrawalThreshold,
        List<QualifyingWithdrawalType> qualifyingWithdrawalTypes) {

    public static final boolean DEFAULT_COUNTER_ENABLED = true;
    public static final int DEFAULT_MONTHLY_THRESHOLD = 4;

    public SavingsProductWithdrawalRule {
        // Enum order, without duplicates, so two rules with the same types compare equal.
        final EnumSet<QualifyingWithdrawalType> types = EnumSet.noneOf(QualifyingWithdrawalType.class);
        types.addAll(qualifyingWithdrawalTypes);
        qualifyingWithdrawalTypes = List.copyOf(types);
    }

    public static SavingsProductWithdrawalRule defaults(final LocalDate effectiveFrom) {
        return new SavingsProductWithdrawalRule(effectiveFrom, DEFAULT_COUNTER_ENABLED, DEFAULT_MONTHLY_THRESHOLD,
                Arrays.asList(QualifyingWithdrawalType.values()));
    }

    public static LocalDate monthStart(final LocalDate date) {
        return date.withDayOfMonth(1);
    }

    /** This rule with whichever of the three rule parameters the command carries, effective from the given month. */
    public SavingsProductWithdrawalRule withChangesFrom(final JsonCommand command, final LocalDate newEffectiveFrom) {
        boolean enabled = this.withdrawalCounterEnabled;
        if (command.parameterExists(withdrawalCounterEnabledParamName)) {
            enabled = command.booleanPrimitiveValueOfParameterNamed(withdrawalCounterEnabledParamName);
        }
        int threshold = this.monthlyWithdrawalThreshold;
        if (command.parameterExists(monthlyWithdrawalThresholdParamName)) {
            threshold = command.integerValueSansLocaleOfParameterNamed(monthlyWithdrawalThresholdParamName);
        }
        List<QualifyingWithdrawalType> types = this.qualifyingWithdrawalTypes;
        if (command.parameterExists(qualifyingWithdrawalTypesParamName)) {
            types = parseTypes(Arrays.asList(command.arrayValueOfParameterNamed(qualifyingWithdrawalTypesParamName)));
        }
        return new SavingsProductWithdrawalRule(newEffectiveFrom, enabled, threshold, types);
    }

    public boolean hasSameTermsAs(final SavingsProductWithdrawalRule other) {
        return this.withdrawalCounterEnabled == other.withdrawalCounterEnabled
                && this.monthlyWithdrawalThreshold == other.monthlyWithdrawalThreshold
                && this.qualifyingWithdrawalTypes.equals(other.qualifyingWithdrawalTypes);
    }

    public List<String> typeCodes() {
        return new ArrayList<>(this.qualifyingWithdrawalTypes.stream().map(Enum::name).toList());
    }

    public String typesCsv() {
        return String.join(",", typeCodes());
    }

    public static List<QualifyingWithdrawalType> parseTypes(final Collection<String> codes) {
        final List<QualifyingWithdrawalType> types = new ArrayList<>();
        for (String code : codes) {
            if (StringUtils.isNotBlank(code)) {
                types.add(QualifyingWithdrawalType.valueOf(code.trim().toUpperCase(Locale.ROOT)));
            }
        }
        return types;
    }

    public static List<QualifyingWithdrawalType> parseTypesCsv(final String csv) {
        return csv == null ? List.of() : parseTypes(Arrays.asList(csv.split(",")));
    }

    public SavingsProductWithdrawalRuleData toData() {
        return new SavingsProductWithdrawalRuleData(DateTimeFormatter.ISO_LOCAL_DATE.format(this.effectiveFrom),
                this.withdrawalCounterEnabled, this.monthlyWithdrawalThreshold, typeCodes());
    }

    /** The latest rule effective on or before the date, else the defaults from that date's month. */
    public static SavingsProductWithdrawalRule inEffectOn(final List<SavingsProductWithdrawalRule> rules, final LocalDate date) {
        SavingsProductWithdrawalRule current = null;
        for (SavingsProductWithdrawalRule rule : rules) {
            if (!rule.effectiveFrom.isAfter(date) && (current == null || rule.effectiveFrom.isAfter(current.effectiveFrom))) {
                current = rule;
            }
        }
        return current != null ? current : defaults(monthStart(date));
    }

    /** The rule in effect on the date followed by every later-dated one, by effective date. */
    public static List<SavingsProductWithdrawalRule> currentAndFuture(final List<SavingsProductWithdrawalRule> rules,
            final LocalDate date) {
        final Map<LocalDate, SavingsProductWithdrawalRule> byDate = new LinkedHashMap<>();
        final SavingsProductWithdrawalRule current = inEffectOn(rules, date);
        byDate.put(current.effectiveFrom, current);
        rules.stream().filter(rule -> rule.effectiveFrom.isAfter(date)).sorted((a, b) -> a.effectiveFrom.compareTo(b.effectiveFrom))
                .forEach(rule -> byDate.put(rule.effectiveFrom, rule));
        return new ArrayList<>(byDate.values());
    }
}
