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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.cob.domain.BatchBusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.jobs.EodCloseOfBusinessTasklet;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.springframework.stereotype.Component;

/**
 * Step 9: the day is closed only when every required step completed and every other configured step that ran completed;
 * the detail is the run's summary. A step that ran but has since been removed from the configuration is reported as a
 * warning, never a blocker, so taking a step out of the chain cannot leave the day unclosable.
 */
@Component
public class EodCompletionStep extends AbstractEodStep {

    public static final String NAME = "EOD_COMPLETION";
    public static final String CODE_INCOMPLETE = "EOD-INCOMPLETE";
    public static final String CODE_STEP_UNCONFIGURED = "EOD-STEP-UNCONFIGURED";

    private final BatchBusinessStepRepository stepConfig;

    public EodCompletionStep(EodRunService runService, FineractProperties properties, BatchBusinessStepRepository stepConfig) {
        super(runService, properties);
        this.stepConfig = stepConfig;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        List<Map<String, Object>> summary = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        Set<String> configured = configuredSteps();
        Set<String> ran = new HashSet<>();
        for (EodStep other : runService.steps(run)) {
            ran.add(other.getStepName());
            if (NAME.equals(other.getStepName())) {
                continue;
            }
            boolean isConfigured = configured.contains(other.getStepName());
            summary.add(detail("step", other.getStepName(), "status", other.getStatus(), "attempts", other.getAttempts(), "startedAt",
                    String.valueOf(other.getStartedAt()), "completedAt", String.valueOf(other.getCompletedAt()), "configured",
                    isConfigured));
            if (other.isCompleted()) {
                continue;
            }
            if (isConfigured) {
                incomplete.add(other.getStepName() + " " + other.getStatus());
            } else {
                runService.recordException(run, NAME, EodException.SEVERITY_WARN, CODE_STEP_UNCONFIGURED, other.getStepName() + " is "
                        + other.getStatus() + " but no longer configured for the chain; it does not block the day", other.getStepName());
            }
        }
        for (String required : EodStepPlan.REQUIRED) {
            if (!NAME.equals(required) && !ran.contains(required)) {
                incomplete.add(required + " NOT_RUN");
            }
        }
        if (!incomplete.isEmpty()) {
            throw new EodStepFailedException(CODE_INCOMPLETE, "steps not completed for " + run.getBusinessDate() + ": " + incomplete);
        }
        return detail("businessDate", run.getBusinessDate().toString(), "steps", summary);
    }

    /** The chain's configured steps; the required ones always count, whatever the stored rows say. */
    private Set<String> configuredSteps() {
        Set<String> configured = new HashSet<>(EodStepPlan.REQUIRED);
        for (BatchBusinessStep row : stepConfig.findAllByJobName(EodCloseOfBusinessTasklet.JOB_NAME)) {
            configured.add(row.getStepName());
        }
        return configured;
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD completion: confirm every step of the day completed";
    }
}
