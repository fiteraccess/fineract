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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rule every EOD step configuration must meet, checked when the configuration is saved and again before a run: all
 * required steps present, each once, in dependency order, with unique order values and {@link EodCompletionStep} last.
 * Other EOD steps may sit anywhere before completion.
 */
public final class EodStepPlan {

    public static final List<String> REQUIRED = List.of(EodRolloverStep.NAME, EodReplayDrainGateStep.NAME, EodAccrualStep.NAME,
            EodInterestPostingStep.NAME, EodBillsGateStep.NAME, EodCompletionStep.NAME);

    public record Entry(String stepName, Long order) {
    }

    private EodStepPlan() {}

    /** Every way {@code entries} breaks the rule; empty when the configuration is runnable. */
    public static List<String> problems(Collection<Entry> entries) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Long, List<String>> byOrder = new TreeMap<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.stepName())) {
                problems.add(entry.stepName() + " is configured more than once");
            }
            if (entry.order() == null) {
                problems.add(entry.stepName() + " has no order");
            } else {
                byOrder.computeIfAbsent(entry.order(), order -> new ArrayList<>()).add(entry.stepName());
            }
        }
        byOrder.forEach((order, names) -> {
            if (names.size() > 1) {
                problems.add("order " + order + " is used by " + names);
            }
        });
        List<String> missing = REQUIRED.stream().filter(name -> !seen.contains(name)).toList();
        if (!missing.isEmpty()) {
            problems.add("required steps missing: " + missing);
        }
        List<String> requiredByOrder = entries.stream().filter(entry -> entry.order() != null && REQUIRED.contains(entry.stepName()))
                .sorted(Comparator.comparing(Entry::order)).map(Entry::stepName).distinct().toList();
        List<String> expected = REQUIRED.stream().filter(requiredByOrder::contains).toList();
        if (!requiredByOrder.equals(expected)) {
            problems.add("required steps must run in the order " + expected + ", configured " + requiredByOrder);
        }
        entries.stream().filter(entry -> entry.order() != null).max(Comparator.comparing(Entry::order))
                .filter(last -> seen.contains(EodCompletionStep.NAME) && !Objects.equals(last.stepName(), EodCompletionStep.NAME))
                .ifPresent(last -> problems.add(EodCompletionStep.NAME + " must be the last step, " + last.stepName() + " runs after it"));
        return problems;
    }

    public static String describe(List<String> problems) {
        return "invalid EOD step configuration: " + String.join("; ", problems);
    }
}
