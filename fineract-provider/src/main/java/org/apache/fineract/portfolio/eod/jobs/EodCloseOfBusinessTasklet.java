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
package org.apache.fineract.portfolio.eod.jobs;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.cob.COBBusinessStepService;
import org.apache.fineract.cob.data.BusinessStepNameAndOrder;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.cob.eod.EodBusinessStep;
import org.apache.fineract.cob.eod.EodRolloverStep;
import org.apache.fineract.cob.eod.EodStepPlan;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.domain.ScheduledJobDetail;
import org.apache.fineract.infrastructure.jobs.domain.ScheduledJobDetailRepository;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * The EOD close-of-business job: one run per business date, its steps ordered by {@code m_batch_business_steps} under
 * {@code EOD_CLOSE_OF_BUSINESS}. "Run now" resumes the run still open before any new day is opened; a day already
 * closed, or one the calendar has not reached yet (a second trigger after the 18:00 close), is a no-op. Each step runs
 * in its own call so its business events are published when it finishes, not held until the chain ends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EodCloseOfBusinessTasklet implements Tasklet {

    public static final String JOB_NAME = "EOD_CLOSE_OF_BUSINESS";
    public static final String CODE_PREFLIGHT = "EOD-PREFLIGHT";
    public static final String CODE_NO_STEPS = "EOD-NO-STEPS";
    public static final String CODE_STEPS_INVALID = "EOD-STEPS-INVALID";
    public static final String CODE_PREFLIGHT_CONFLICT = "EOD-PREFLIGHT-CONFLICT";
    /** The jobs the chain replaces: either would advance the date again or post the day's accrual or interest twice. */
    static final List<JobName> REPLACED_JOBS = List.of(JobName.INCREASE_BUSINESS_DATE_BY_1_DAY, JobName.INCREASE_COB_DATE_BY_1_DAY,
            JobName.ADD_PERIODIC_ACCRUAL_ENTRIES_FOR_SAVINGS_WITH_INCOME_POSTED_AS_TRANSACTIONS, JobName.POST_INTEREST_FOR_SAVINGS);

    private final ConfigurationDomainService configurationDomainService;
    private final EodRunService runService;
    private final COBBusinessStepService cobBusinessStepService;
    private final BatchBusinessStepRepository batchBusinessStepRepository;
    private final ScheduledJobDetailRepository scheduledJobDetailRepository;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        if (!configurationDomainService.isBusinessDateEnabled()) {
            throw new EodStepFailedException(CODE_PREFLIGHT, "enable-business-date is off in Fineract; the EOD chain needs it");
        }
        List<String> conflicting = activeReplacedJobs();
        if (!conflicting.isEmpty()) {
            throw new EodStepFailedException(CODE_PREFLIGHT_CONFLICT,
                    "deactivate the jobs the EOD chain replaces before it runs; still active: " + conflicting);
        }
        Optional<EodRun> open = runService.findOpenRun();
        LocalDate businessDate = open.map(EodRun::getBusinessDate)
                .orElseGet(() -> ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
        if (open.isEmpty() && runService.findRun(businessDate).map(EodRun::isCompleted).orElse(false)) {
            log.info("EOD {}: already completed, nothing to do", businessDate);
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }
        LocalDate calendarToday = DateUtils.getLocalDateOfTenant();
        if (open.isEmpty() && businessDate.isAfter(calendarToday)) {
            log.info("EOD {}: not closed before its calendar day (today is {}), nothing to do", businessDate, calendarToday);
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }
        EodRun run = runService.findOrStart(businessDate);
        log.info("EOD {}: {} (attempt {})", businessDate, open.isPresent() ? "resuming" : "starting", run.getAttempts());

        try {
            requireRunnablePlan();
            if (runService.findStep(run, EodRolloverStep.NAME).map(EodStep::isCompleted).orElse(false)) {
                EodRolloverStep.useClosingDates(businessDate);
            }
            for (var step : executionMap().entrySet()) {
                cobBusinessStepService.run(new TreeMap<>(Map.of(step.getKey(), step.getValue())), run);
            }
            runService.completeRun(run);
            log.info("EOD {}: completed", businessDate);
        } catch (RuntimeException e) {
            runService.failRun(run, String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            throw e;
        }
        return RepeatStatus.FINISHED;
    }

    private List<String> activeReplacedJobs() {
        return REPLACED_JOBS.stream().map(JobName::toString).filter(name -> {
            ScheduledJobDetail job = scheduledJobDetailRepository.findByJobName(name);
            return job != null && job.isActiveSchedular();
        }).toList();
    }

    /**
     * Checked on the stored rows, before they become a map keyed by order that would let one step overwrite another.
     */
    private void requireRunnablePlan() {
        List<String> problems = EodStepPlan.problems(batchBusinessStepRepository.findAllByJobName(JOB_NAME).stream()
                .map(step -> new EodStepPlan.Entry(step.getStepName(), step.getStepOrder())).toList());
        if (!problems.isEmpty()) {
            throw new EodStepFailedException(CODE_STEPS_INVALID, EodStepPlan.describe(problems));
        }
    }

    private TreeMap<Long, String> executionMap() {
        Set<BusinessStepNameAndOrder> steps = cobBusinessStepService.getCOBBusinessSteps(EodBusinessStep.class, JOB_NAME);
        TreeMap<Long, String> executionMap = new TreeMap<>();
        for (BusinessStepNameAndOrder step : steps) {
            executionMap.put(step.getStepOrder(), step.getStepName());
        }
        if (executionMap.isEmpty()) {
            throw new EodStepFailedException(CODE_NO_STEPS, "no business steps are configured for " + JOB_NAME);
        }
        return executionMap;
    }
}
