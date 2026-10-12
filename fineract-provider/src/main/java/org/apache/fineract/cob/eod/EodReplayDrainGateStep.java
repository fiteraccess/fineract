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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseReplayStatus;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step 0b, the replay-drain gate: nothing dated on or before D may still be short of Fineract when accrual runs,
 * because accrual computes on Fineract's transactions. After a grace period for requests that read D just before the
 * flip, the step polls Synapse until everything has drained. A failure newer than the last completed run fails the run
 * at once; legacy failures become exception rows and do not block.
 */
@Component
public class EodReplayDrainGateStep extends AbstractEodStep {

    public static final String NAME = "EOD_REPLAY_DRAIN";
    public static final String CODE_REPLAY_FAILED = "EOD-REPLAY-FAILED";
    public static final String CODE_REPLAY_LEGACY = "EOD-REPLAY-LEGACY";

    private final ObjectProvider<SynapseTransactionClient> synapseClient;

    public EodReplayDrainGateStep(EodRunService runService, FineractProperties properties,
            ObjectProvider<SynapseTransactionClient> synapseClient) {
        super(runService, properties);
        this.synapseClient = synapseClient;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        requirePrerequisite(run, EodRolloverStep.NAME);
        SynapseTransactionClient client = requireSynapse(synapseClient);
        waitForGrace(run, step);

        List<SynapseReplayStatus.Failure> legacy = new ArrayList<>();
        Map<String, Object> detail = waitUntil(step, () -> {
            SynapseReplayStatus status = client.getReplayStatus(run.getBusinessDate());
            if (status.getFailedNewCount() > 0) {
                throw new EodStepFailedException(CODE_REPLAY_FAILED,
                        status.getFailedNewCount() + " replay failure(s) dated after the cutoff and on or before " + run.getBusinessDate()
                                + ": " + references(status.getFailedNew()));
            }
            legacy.clear();
            if (status.getFailedLegacy() != null) {
                legacy.addAll(status.getFailedLegacy());
            }
            SynapseReplayStatus.InFlight inFlight = status.getInFlight();
            Map<String, Object> seen = detail("phase", "drain", "inFlight", inFlight == null ? null : inFlight.getTotal(), "pendingTx",
                    inFlight == null ? null : inFlight.getPendingTx(), "postedToTbOnly",
                    inFlight == null ? null : inFlight.getPostedToTbOnly(), "inboxPending",
                    inFlight == null ? null : inFlight.getInboxPending(), "inboxRetrying",
                    inFlight == null ? null : inFlight.getInboxRetrying(), "flexcubeOutboxPending", status.getFlexcubeOutboxPending(),
                    "failedLegacy", status.getFailedLegacyCount(), "cutoff", String.valueOf(status.getCutoff()), "cutoffSource",
                    status.getCutoffSource(), "drained", status.isDrained());
            return status.isDrained() ? Poll.done(seen) : Poll.waiting(seen);
        });
        for (SynapseReplayStatus.Failure failure : legacy) {
            runService.recordException(
                    run, NAME, EodException.SEVERITY_WARN, CODE_REPLAY_LEGACY, failure.getType() + " " + failure.getStatus() + " dated "
                            + failure.getTransactionDate() + (failure.getError() == null ? "" : ": " + failure.getError()),
                    failure.getReference());
        }
        return detail;
    }

    /** Requests that read D just before the flip may still be posting; give them the configured grace. */
    private void waitForGrace(EodRun run, EodStep step) {
        LocalDateTime rolledAt = runService.findStep(run, EodRolloverStep.NAME).map(EodStep::getCompletedAt).orElse(null);
        if (rolledAt == null) {
            return;
        }
        LocalDateTime until = rolledAt.plus(Duration.ofSeconds(properties.getEod().getReplayGraceSeconds()));
        Duration interval = Duration.ofSeconds(properties.getEod().getPollIntervalSeconds());
        while (DateUtils.getLocalDateTimeOfTenant().isBefore(until)) {
            runService.waiting(step, detail("phase", "grace", "until", until.toString()));
            Duration remaining = Duration.between(DateUtils.getLocalDateTimeOfTenant(), until);
            sleep(remaining.compareTo(interval) < 0 ? remaining : interval);
        }
    }

    private static String references(List<SynapseReplayStatus.Failure> failures) {
        List<String> refs = new ArrayList<>();
        if (failures == null) {
            return "(no sample)";
        }
        for (SynapseReplayStatus.Failure failure : failures) {
            refs.add(failure.getReference() + " (" + failure.getSource() + " " + failure.getType() + " " + failure.getStatus() + ")");
        }
        return String.join(", ", refs);
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD replay-drain gate: wait until everything dated the closing day has reached Fineract";
    }
}
