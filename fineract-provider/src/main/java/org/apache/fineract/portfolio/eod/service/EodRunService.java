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
package org.apache.fineract.portfolio.eod.service;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodExceptionRepository;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodRunRepository;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.domain.EodStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chain's checkpoints. Every write commits on its own ({@code REQUIRES_NEW}) so a step's status survives whatever
 * the step does next, and a resumed run finds exactly where the last one stopped.
 */
@Service
@RequiredArgsConstructor
public class EodRunService {

    private static final Gson GSON = new Gson();

    private final EodRunRepository runRepository;
    private final EodStepRepository stepRepository;
    private final EodExceptionRepository exceptionRepository;

    /** The run for {@code businessDate}: the existing one, re-attempted, or a new one. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EodRun findOrStart(LocalDate businessDate) {
        Optional<EodRun> existing = runRepository.findByBusinessDate(businessDate);
        if (existing.isPresent()) {
            EodRun run = existing.get();
            if (!run.isCompleted()) {
                run.setStatus(EodRun.STATUS_RUNNING);
                run.setAttempts(run.getAttempts() + 1);
                run.setLastError(null);
                return runRepository.saveAndFlush(run);
            }
            return run;
        }
        return runRepository.saveAndFlush(EodRun.start(businessDate, now()));
    }

    /** The step's checkpoint: a completed one is returned as is (the caller skips it), any other is (re)started. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EodStep beginStep(EodRun run, String stepName) {
        Optional<EodStep> existing = stepRepository.findByRunAndStepName(run, stepName);
        if (existing.isPresent()) {
            EodStep step = existing.get();
            if (step.isCompleted()) {
                return step;
            }
            step.setStatus(EodStep.STATUS_RUNNING);
            step.setAttempts(step.getAttempts() + 1);
            step.setStartedAt(now());
            step.setErrorCode(null);
            step.setErrorMessage(null);
            return stepRepository.saveAndFlush(step);
        }
        return stepRepository.saveAndFlush(EodStep.start(run, stepName, now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void waiting(EodStep step, Map<String, Object> detail) {
        step.setStatus(EodStep.STATUS_WAITING);
        step.setDetail(json(detail));
        stepRepository.saveAndFlush(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completed(EodStep step, Map<String, Object> detail) {
        step.setStatus(EodStep.STATUS_COMPLETED);
        step.setCompletedAt(now());
        step.setDetail(json(detail));
        stepRepository.saveAndFlush(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(EodStep step, String code, String message) {
        step.setStatus(EodStep.STATUS_FAILED);
        step.setErrorCode(code);
        step.setErrorMessage(truncate(message, 2000));
        stepRepository.saveAndFlush(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRun(EodRun run) {
        run.setStatus(EodRun.STATUS_COMPLETED);
        run.setCompletedAt(now());
        run.setLastError(null);
        runRepository.saveAndFlush(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(EodRun run, String error) {
        run.setStatus(EodRun.STATUS_FAILED);
        run.setLastError(truncate(error, 1000));
        runRepository.saveAndFlush(run);
    }

    /**
     * One row per run, step, code and reference: a retried or resumed step reports the same exception again, and the
     * report must not count it twice. The chain runs on one thread, so the check before the insert cannot race.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordException(EodRun run, String stepName, String severity, String code, String message, String reference) {
        boolean recorded = reference == null ? exceptionRepository.existsByRunAndStepNameAndCodeAndReferenceIsNull(run, stepName, code)
                : exceptionRepository.existsByRunAndStepNameAndCodeAndReference(run, stepName, code, reference);
        if (!recorded) {
            exceptionRepository.saveAndFlush(EodException.of(run, stepName, severity, code, truncate(message, 2000), reference, now()));
        }
    }

    @Transactional(readOnly = true)
    public Optional<EodStep> findStep(EodRun run, String stepName) {
        return stepRepository.findByRunAndStepName(run, stepName);
    }

    @Transactional(readOnly = true)
    public List<EodStep> steps(EodRun run) {
        return stepRepository.findByRunOrderByIdAsc(run);
    }

    @Transactional(readOnly = true)
    public Optional<EodRun> findOpenRun() {
        return runRepository.findFirstByStatusNotOrderByBusinessDateAsc(EodRun.STATUS_COMPLETED);
    }

    @Transactional(readOnly = true)
    public Optional<EodRun> findRun(LocalDate businessDate) {
        return runRepository.findByBusinessDate(businessDate);
    }

    private static String json(Map<String, Object> detail) {
        return detail == null ? null : GSON.toJson(detail);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime now() {
        return DateUtils.getLocalDateTimeOfTenant();
    }
}
