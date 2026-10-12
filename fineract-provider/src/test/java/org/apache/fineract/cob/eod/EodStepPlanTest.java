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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EodStepPlanTest {

    /** The configuration changeset 0253 seeds. */
    private static List<EodStepPlan.Entry> seeded() {
        return new ArrayList<>(
                List.of(new EodStepPlan.Entry(EodRolloverStep.NAME, 1L), new EodStepPlan.Entry(EodReplayDrainGateStep.NAME, 2L),
                        new EodStepPlan.Entry(EodAccrualStep.NAME, 3L), new EodStepPlan.Entry(EodInterestPostingStep.NAME, 4L),
                        new EodStepPlan.Entry(EodBillsGateStep.NAME, 5L), new EodStepPlan.Entry(EodCompletionStep.NAME, 9L)));
    }

    @Test
    void theSeededConfigurationIsRunnable() {
        assertEquals(List.of(), EodStepPlan.problems(seeded()));
    }

    @Test
    void anotherEodStepBeforeCompletionIsAllowed() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.add(new EodStepPlan.Entry("EOD_MIRROR", 6L));

        assertEquals(List.of(), EodStepPlan.problems(entries));
    }

    @Test
    void aMissingRequiredStepIsNamed() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.removeIf(entry -> entry.stepName().equals(EodBillsGateStep.NAME));

        assertEquals(List.of("required steps missing: [" + EodBillsGateStep.NAME + "]"), EodStepPlan.problems(entries));
    }

    @Test
    void aDuplicateOrderIsNamed() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.add(new EodStepPlan.Entry("EOD_MIRROR", 5L));

        assertEquals(List.of("order 5 is used by [" + EodBillsGateStep.NAME + ", EOD_MIRROR]"), EodStepPlan.problems(entries));
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
    void completionMustBeLast() {
        List<EodStepPlan.Entry> entries = seeded();
        entries.add(new EodStepPlan.Entry("EOD_MIRROR", 10L));

        assertEquals(List.of(EodCompletionStep.NAME + " must be the last step, EOD_MIRROR runs after it"), EodStepPlan.problems(entries));
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
