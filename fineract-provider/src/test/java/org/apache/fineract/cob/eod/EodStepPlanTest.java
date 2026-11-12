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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EodStepPlanTest {

    /**
     * The configuration of a tenant with no steps of its own: changeset 0253, the Bills gate removed by 0263, and month
     * end and year end placed before completion by 0270.
     */
    private static List<EodStepPlan.Entry> seeded() {
        return new ArrayList<>(List.of(new EodStepPlan.Entry(EodRolloverStep.NAME, 1L),
                new EodStepPlan.Entry(EodReplayDrainGateStep.NAME, 2L), new EodStepPlan.Entry(EodAccrualStep.NAME, 3L),
                new EodStepPlan.Entry(EodInterestPostingStep.NAME, 4L), new EodStepPlan.Entry(EodMonthEndStep.NAME, 8L),
                new EodStepPlan.Entry(EodYearEndStep.NAME, 9L), new EodStepPlan.Entry(EodCompletionStep.NAME, 10L)));
    }

    @Test
    void theSeededConfigurationIsRunnable() {
        assertEquals(List.of(), EodStepPlan.problems(seeded()));
    }

    /**
     * The configuration once changeset 0256 added the GL steps (mirrors 5 to 8, zero balance 9) and 0270 placed month
     * end and year end at 10 and 11 with completion at 12.
     */
    @Test
    void theSeededConfigurationWithTheGlStepsIsRunnable() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.replaceAll(entry -> switch (entry.stepName()) {
            case EodMonthEndStep.NAME -> new EodStepPlan.Entry(EodMonthEndStep.NAME, 10L);
            case EodYearEndStep.NAME -> new EodStepPlan.Entry(EodYearEndStep.NAME, 11L);
            case EodCompletionStep.NAME -> new EodStepPlan.Entry(EodCompletionStep.NAME, 12L);
            default -> entry;
        });
        entries.addAll(List.of(new EodStepPlan.Entry(EodMirrorDepositStep.NAME, 5L), new EodStepPlan.Entry(EodMirrorIncomeStep.NAME, 6L),
                new EodStepPlan.Entry(EodMirrorTaxStep.NAME, 7L), new EodStepPlan.Entry(EodMirrorInterestStep.NAME, 8L),
                new EodStepPlan.Entry(EodZeroBalanceStep.NAME, 9L)));

        assertEquals(List.of(), EodStepPlan.problems(entries));
    }

    @Test
    void theGlStepsAreOptional() {
        assertTrue(EodStepPlan.REQUIRED.stream().noneMatch(name -> name.startsWith("EOD_MIRROR") || name.equals(EodZeroBalanceStep.NAME)));
    }

    @Test
    void monthEndAndYearEndAreRequired() {
        assertTrue(EodStepPlan.REQUIRED.containsAll(List.of(EodMonthEndStep.NAME, EodYearEndStep.NAME)));
    }

    @Test
    void theBillsGateIsNoLongerRequired() {
        assertFalse(EodStepPlan.REQUIRED.contains("EOD_BILLS_GATE"));
    }

    @Test
    void anotherEodStepBeforeMonthEndIsAllowed() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.add(new EodStepPlan.Entry("EOD_MIRROR", 6L));

        assertEquals(List.of(), EodStepPlan.problems(entries));
    }

    /**
     * The zero-balance check posts nothing, but it reads a period month end is about to close: nothing may follow month
     * end.
     */
    @Test
    void aStepAfterMonthEndIsRefused_namingIt() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.replaceAll(entry -> switch (entry.stepName()) {
            case EodMonthEndStep.NAME -> new EodStepPlan.Entry(EodMonthEndStep.NAME, 5L);
            case EodYearEndStep.NAME -> new EodStepPlan.Entry(EodYearEndStep.NAME, 6L);
            default -> entry;
        });
        entries.add(new EodStepPlan.Entry(EodZeroBalanceStep.NAME, 7L));

        assertEquals(List.of(EodZeroBalanceStep.NAME + " must run before " + EodMonthEndStep.NAME
                + ", which closes the period; it is configured after it"), EodStepPlan.problems(entries));
    }

    @Test
    void aMissingRequiredStepIsNamed() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.removeIf(entry -> entry.stepName().equals(EodInterestPostingStep.NAME));

        assertEquals(List.of("required steps missing: [" + EodInterestPostingStep.NAME + "]"), EodStepPlan.problems(entries));
    }

    @Test
    void aDuplicateOrderIsNamed() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.add(new EodStepPlan.Entry("EOD_MIRROR", 4L));

        assertEquals(List.of("order 4 is used by [" + EodInterestPostingStep.NAME + ", EOD_MIRROR]"), EodStepPlan.problems(entries));
    }

    @Test
    void requiredStepsOutOfDependencyOrderAreRefused() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.replaceAll(entry -> switch (entry.stepName()) {
            case EodAccrualStep.NAME -> new EodStepPlan.Entry(EodAccrualStep.NAME, 4L);
            case EodInterestPostingStep.NAME -> new EodStepPlan.Entry(EodInterestPostingStep.NAME, 3L);
            default -> entry;
        });

        List<String> problems = EodStepPlan.problems(entries);

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().startsWith("required steps must run in the order"));
    }

    @Test
    void yearEndBeforeMonthEndIsRefused() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.replaceAll(entry -> switch (entry.stepName()) {
            case EodMonthEndStep.NAME -> new EodStepPlan.Entry(EodMonthEndStep.NAME, 9L);
            case EodYearEndStep.NAME -> new EodStepPlan.Entry(EodYearEndStep.NAME, 8L);
            default -> entry;
        });

        List<String> problems = EodStepPlan.problems(entries);

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().startsWith("required steps must run in the order"));
    }

    @Test
    void completionMustBeLast() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.add(new EodStepPlan.Entry("EOD_MIRROR", 11L));

        assertEquals(List.of("EOD_MIRROR must run before " + EodMonthEndStep.NAME + ", which closes the period; it is configured after it",
                EodCompletionStep.NAME + " must be the last step, EOD_MIRROR runs after it"), EodStepPlan.problems(entries));
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        List<EodStepPlan.Entry> entries = new ArrayList<>(List.of(new EodStepPlan.Entry(EodCompletionStep.NAME, 1L),
                new EodStepPlan.Entry(EodRolloverStep.NAME, 1L), new EodStepPlan.Entry(EodRolloverStep.NAME, 2L)));

        List<String> problems = EodStepPlan.problems(entries);

        assertTrue(problems.contains(EodRolloverStep.NAME + " is configured more than once"));
        assertTrue(problems.contains("order 1 is used by [" + EodCompletionStep.NAME + ", " + EodRolloverStep.NAME + "]"));
        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("required steps missing")));
        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith(EodCompletionStep.NAME + " must be the last step")));
    }
}
