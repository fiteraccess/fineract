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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseSettlementStatus;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step 3, the Bills gate: the day's Bills settlement is Synapse's own work; this step only confirms it finished. A
 * failed or reconciliation-bound batch loaded after the cutoff fails the run, older ones are exception rows every day
 * until resolved (as the replay gate treats its legacy failures); a batch still in flight is waited for. The
 * end-of-cycle acknowledgement never holds the day: batches awaiting it are recorded in the detail only. No Bills file
 * at all for the day is an exception row, not a failure.
 */
@Component
public class EodBillsGateStep extends AbstractEodStep {

    public static final String NAME = "EOD_BILLS_GATE";
    public static final String CODE_BILLS_FAILED = "EOD-BILLS-FAILED";
    public static final String CODE_BILLS_LEGACY_FAILED = "EOD-BILLS-LEGACY-FAILED";
    public static final String CODE_BILLS_NO_FILE = "EOD-BILLS-NO-FILE";

    private final ObjectProvider<SynapseTransactionClient> synapseClient;

    public EodBillsGateStep(EodRunService runService, FineractProperties properties,
            ObjectProvider<SynapseTransactionClient> synapseClient) {
        super(runService, properties);
        this.synapseClient = synapseClient;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        requirePrerequisite(run, EodReplayDrainGateStep.NAME);
        SynapseTransactionClient client = requireSynapse(synapseClient);
        AtomicLong loaded = new AtomicLong(-1);
        List<SynapseSettlementStatus.BatchFailure> legacy = new ArrayList<>();
        Map<String, Object> detail = waitUntil(step, () -> {
            SynapseSettlementStatus status = client.getSettlementStatus(run.getBusinessDate());
            if (status.getFailedNewCount() > 0) {
                throw new EodStepFailedException(CODE_BILLS_FAILED,
                        status.getFailedNewCount() + " Bills settlement batch(es) loaded after the cutoff failed or need reconciliation: "
                                + batches(status.getFailedNew()));
            }
            legacy.clear();
            if (status.getFailedLegacy() != null) {
                legacy.addAll(status.getFailedLegacy());
            }
            loaded.set(status.getLoadedForDate());
            Map<String, Object> seen = detail("byStatus", status.getByStatus(), "inFlight", status.getInFlight(), "awaitingEoc",
                    status.getAwaitingEoc(), "loadedForDate", status.getLoadedForDate(), "failedLegacy", status.getFailedLegacyCount(),
                    "cutoff", String.valueOf(status.getCutoff()), "cutoffSource", status.getCutoffSource(), "settled", status.isSettled());
            return status.isSettled() ? Poll.done(seen) : Poll.waiting(seen);
        });
        for (SynapseSettlementStatus.BatchFailure failure : legacy) {
            runService.recordException(
                    run, NAME, EodException.SEVERITY_WARN, CODE_BILLS_LEGACY_FAILED, "Bills batch " + failure.getStatus() + " loaded "
                            + failure.getLoadedDate() + (failure.getLastError() == null ? "" : ": " + failure.getLastError()),
                    failure.getBatchId());
        }
        if (loaded.get() == 0) {
            runService.recordException(run, NAME, EodException.SEVERITY_WARN, CODE_BILLS_NO_FILE,
                    "No Bills settlement file was loaded for " + run.getBusinessDate(), null);
        }
        return detail;
    }

    private static String batches(List<SynapseSettlementStatus.BatchFailure> failures) {
        if (failures == null) {
            return "(no sample)";
        }
        List<String> batches = new ArrayList<>();
        for (SynapseSettlementStatus.BatchFailure failure : failures) {
            batches.add(failure.getBatchId() + " " + failure.getStatus()
                    + (failure.getLastError() == null ? "" : ": " + failure.getLastError()));
        }
        return batches.toString();
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD Bills gate: confirm the day's Bills settlement finished in Synapse";
    }
}
