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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * What every EOD step shares: the checkpoint (a step already COMPLETED for this run is skipped, so "Run now" resumes),
 * the prerequisite check (steps are reorderable in Manage Jobs, so each one validates what it depends on), and the
 * WAITING loop (poll Synapse until a condition holds, or fail after {@code fineract.eod.step-timeout-minutes}).
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractEodStep implements EodBusinessStep {

    public static final String CODE_PREREQUISITE = "EOD-PREREQUISITE";
    public static final String CODE_TIMEOUT = "EOD-STEP-TIMEOUT";
    public static final String CODE_ERROR = "EOD-STEP-ERROR";
    public static final String CODE_SYNAPSE_DISABLED = "EOD-SYNAPSE-DISABLED";
    public static final String CODE_SYNAPSE_REFUSED = "EOD-SYNAPSE-REFUSED";
    public static final String CODE_POLL_ERROR = "EOD-POLL-ERROR";

    protected final EodRunService runService;
    protected final FineractProperties properties;

    /** The outcome of one poll: {@code done} ends the wait; {@code detail} is what the checkpoint records meanwhile. */
    public record Poll(boolean done, Map<String, Object> detail) {

        public static Poll done(Map<String, Object> detail) {
            return new Poll(true, detail);
        }

        public static Poll waiting(Map<String, Object> detail) {
            return new Poll(false, detail);
        }
    }

    @Override
    public EodRun execute(EodRun run) {
        EodStep step = runService.beginStep(run, getEnumStyledName());
        if (step.isCompleted()) {
            log.info("EOD {}: step {} already completed, skipping", run.getBusinessDate(), getEnumStyledName());
            return run;
        }
        try {
            Map<String, Object> detail = run(run, step);
            runService.completed(step, detail);
            log.info("EOD {}: step {} completed", run.getBusinessDate(), getEnumStyledName());
            return run;
        } catch (EodStepFailedException e) {
            log.error("EOD {}: step {} failed [{}]: {}", run.getBusinessDate(), getEnumStyledName(), e.getCode(), e.getMessage());
            runService.failed(step, e.getCode(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("EOD {}: step {} failed", run.getBusinessDate(), getEnumStyledName(), e);
            runService.failed(step, CODE_ERROR, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    /** The step's work; what it returns is recorded as the step's detail. */
    protected abstract Map<String, Object> run(EodRun run, EodStep step);

    protected void requirePrerequisite(EodRun run, String stepName) {
        boolean completed = runService.findStep(run, stepName).map(EodStep::isCompleted).orElse(false);
        if (!completed) {
            throw new EodStepFailedException(CODE_PREREQUISITE,
                    getEnumStyledName() + " requires " + stepName + " to be COMPLETED for " + run.getBusinessDate());
        }
    }

    /**
     * Polls until {@code poll} reports done, recording each poll's detail on the WAITING checkpoint, and fails the step
     * once the configured timeout has elapsed. Returns the last detail. A step failure or a 4xx answer from Synapse
     * ends the wait at once, since asking again cannot change it; any other error (I/O, timeout, 5xx, a pod restarting)
     * is recorded as {@code lastError} and the next poll tries again.
     */
    protected Map<String, Object> waitUntil(EodStep step, Supplier<Poll> poll) {
        Duration timeout = Duration.ofMinutes(properties.getEod().getStepTimeoutMinutes());
        Duration interval = Duration.ofSeconds(properties.getEod().getPollIntervalSeconds());
        Instant deadline = Instant.now().plus(timeout);
        int polls = 0;
        String lastError = null;
        Map<String, Object> lastSeen = Map.of();
        while (true) {
            polls++;
            Map<String, Object> detail;
            try {
                Poll outcome = poll.get();
                detail = new LinkedHashMap<>(outcome.detail() == null ? Map.of() : outcome.detail());
                detail.put("polls", polls);
                if (outcome.done()) {
                    return detail;
                }
                lastSeen = outcome.detail() == null ? Map.of() : outcome.detail();
            } catch (EodStepFailedException e) {
                throw e;
            } catch (RuntimeException e) {
                requireTransient(e);
                lastError = String.valueOf(e.getMessage());
                log.warn("EOD step {}: poll {} failed, polling again: {}", getEnumStyledName(), polls, lastError);
                detail = new LinkedHashMap<>(lastSeen);
                detail.put("polls", polls);
            }
            if (lastError != null) {
                detail.put("lastError", lastError);
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new EodStepFailedException(CODE_TIMEOUT,
                        getEnumStyledName() + " still waiting after " + timeout.toMinutes() + " minutes: " + detail);
            }
            runService.waiting(step, detail);
            sleep(interval);
        }
    }

    /**
     * Only an outage is worth the next poll: a connection error, a timeout, a 5xx or a 429. Any other 4xx is a refusal
     * (401/403 mean the service user lacks the route's permission) and anything else is a defect; both end the wait.
     */
    private void requireTransient(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) {
                return;
            }
            if (cause instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is5xxServerError() || status == 429) {
                    return;
                }
                String why = status == 401 || status == 403
                        ? "the Fineract service user calling Synapse lacks the route's permission (READ_EODSTATUS for the gates)"
                        : "Synapse refused the request";
                throw new EodStepFailedException(CODE_SYNAPSE_REFUSED,
                        getEnumStyledName() + ": HTTP " + status + ", " + why + ": " + e.getMessage(), e);
            }
        }
        throw new EodStepFailedException(CODE_POLL_ERROR, getEnumStyledName() + ": poll failed: " + e, e);
    }

    /** The chain cannot run without Synapse: a disabled client (no bean) fails the step plainly. */
    protected SynapseTransactionClient requireSynapse(ObjectProvider<SynapseTransactionClient> provider) {
        SynapseTransactionClient client = provider.getIfAvailable();
        if (client == null) {
            throw new EodStepFailedException(CODE_SYNAPSE_DISABLED,
                    "fineract.synapse.enabled is off: " + getEnumStyledName() + " needs Synapse");
        }
        return client;
    }

    protected void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EodStepFailedException(CODE_ERROR, getEnumStyledName() + " interrupted while waiting", e);
        }
    }

    protected static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            detail.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return detail;
    }
}
