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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodGlResult;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseMirrorLeg;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseMirrorRun;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Steps 3 to 6, one per GL mirror job: start Synapse's mirror run for the day (idempotent, a FAILED run is reopened),
 * wait for it to complete, then keep its legs as GL result rows and flag every leg whose Fineract movement drifted from
 * what was mirrored. Each job after DEPOSIT runs only once the job before it completed.
 */
public abstract class EodMirrorStep extends AbstractEodStep {

    public static final String CODE_MIRROR_REFUSED = "EOD-MIRROR-REFUSED";
    public static final String CODE_MIRROR_FAILED = "EOD-MIRROR-FAILED";
    public static final String CODE_MIRROR_RECONCILE = "EOD-MIRROR-RECONCILE";
    public static final String CODE_MIRROR_DRIFT = "EOD-MIRROR-DRIFT";

    private final ObjectProvider<SynapseTransactionClient> synapseClient;
    private final GLAccountRepository glAccounts;

    protected EodMirrorStep(EodRunService runService, FineractProperties properties, ObjectProvider<SynapseTransactionClient> synapseClient,
            GLAccountRepository glAccounts) {
        super(runService, properties);
        this.synapseClient = synapseClient;
        this.glAccounts = glAccounts;
    }

    /** The Synapse mirror job: DEPOSIT, INCOME, TAX or INTEREST. */
    protected abstract String job();

    /** The step that must have completed first. */
    protected abstract String previousStep();

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        requirePrerequisite(run, EodInterestPostingStep.NAME);
        if (!EodInterestPostingStep.NAME.equals(previousStep())) {
            requirePrerequisite(run, previousStep());
        }
        SynapseTransactionClient client = requireSynapse(synapseClient);
        try {
            client.startMirrorRun(run.getBusinessDate(), job());
        } catch (SynapsePostingException e) {
            throw new EodStepFailedException(isRefusal(e) ? CODE_MIRROR_REFUSED : EodRolloverStep.CODE_SYNAPSE_PUSH, e.getMessage(), e);
        }
        AtomicReference<SynapseMirrorRun> completed = new AtomicReference<>();
        Map<String, Object> polled = waitUntil(step, () -> {
            SynapseMirrorRun view = client.getMirrorRun(run.getBusinessDate(), job());
            completed.set(view);
            return outcome(run, view);
        });
        List<SynapseMirrorLeg> legs = legs(completed.get());
        runService.replaceGlResults(run, getEnumStyledName(), results(legs));
        int drift = 0;
        for (SynapseMirrorLeg leg : legs) {
            if (SynapseMirrorLeg.DRIFT.equals(leg.getDriftStatus())) {
                drift++;
                runService
                        .recordException(run, getEnumStyledName(), EodException.SEVERITY_WARN, CODE_MIRROR_DRIFT,
                                job() + " row " + leg.getRowNo() + " " + leg.getLogicalName() + ": Fineract's movement on GL "
                                        + leg.getFinSourceGlCode() + " differs from the mirrored amount " + leg.getAmount(),
                                leg.getReference());
            }
        }
        return detail("job", job(), "legs", legs.size(), "amountTotal", amountTotal(legs), "skipped",
                legs.stream().filter(leg -> SynapseMirrorLeg.FIN_SKIPPED.equals(leg.getFinStatus())).count(), "drift", drift, "polls",
                polled.get("polls"));
    }

    private Poll outcome(EodRun run, SynapseMirrorRun view) {
        SynapseMirrorRun.Run mirror = view.getRun();
        List<SynapseMirrorLeg> legs = legs(view);
        String status = mirror.getStatus();
        if (SynapseMirrorRun.STATUS_COMPLETED.equals(status)) {
            return Poll.done(detail("status", status));
        }
        if (SynapseMirrorRun.STATUS_FAILED.equals(status)) {
            List<String> failed = new ArrayList<>();
            for (SynapseMirrorLeg leg : legs) {
                if (SynapseMirrorLeg.FIN_FAILED.equals(leg.getFinStatus()) || SynapseMirrorLeg.FLEX_DEAD.equals(leg.getFlexStatus())) {
                    failed.add(leg.getLogicalName() + (leg.getLastError() == null ? "" : ": " + leg.getLastError()));
                }
            }
            throw new EodStepFailedException(CODE_MIRROR_FAILED,
                    job() + " mirror run for " + run.getBusinessDate() + " FAILED: " + mirror.getLastError() + "; failed legs " + failed);
        }
        if (SynapseMirrorRun.STATUS_NEEDS_RECONCILIATION.equals(status)) {
            List<String> rows = new ArrayList<>();
            for (SynapseMirrorLeg leg : legs) {
                if (!SynapseMirrorLeg.FIN_POSTED.equals(leg.getFinStatus()) && !SynapseMirrorLeg.FIN_SKIPPED.equals(leg.getFinStatus())) {
                    rows.add("row " + leg.getRowNo() + " " + leg.getLogicalName() + " " + leg.getFinStatus()
                            + (leg.getLastError() == null ? "" : ": " + leg.getLastError()));
                }
            }
            throw new EodStepFailedException(CODE_MIRROR_RECONCILE, job() + " mirror run for " + run.getBusinessDate()
                    + " needs reconciliation in Synapse before the step is re-run: " + rows);
        }
        return Poll.waiting(detail("status", status, "lastError", mirror.getLastError(), "legs", legs.size(), "finPosted",
                legs.stream().filter(leg -> SynapseMirrorLeg.FIN_POSTED.equals(leg.getFinStatus())).count(), "flexSent",
                legs.stream().filter(leg -> SynapseMirrorLeg.FLEX_SENT.equals(leg.getFlexStatus())).count()));
    }

    private List<EodGlResult> results(List<SynapseMirrorLeg> legs) {
        List<EodGlResult> results = new ArrayList<>(legs.size());
        for (SynapseMirrorLeg leg : legs) {
            EodGlResult result = EodGlResult.of(EodGlResult.KIND_MIRROR_LEG, leg.getRowNo(), leg.getLogicalName());
            result.setFinSourceGl(leg.getFinSourceGlCode());
            result.setFinBridgeGl(glCode(leg.getFinBridgeGlId()));
            if (leg.getFlexSource() != null) {
                result.setFlexSourceGl(leg.getFlexSource().getGl());
                result.setFlexSourceBranch(leg.getFlexSource().getBranch());
            }
            if (leg.getFlexBridge() != null) {
                result.setFlexBridgeGl(leg.getFlexBridge().getGl());
                result.setFlexBridgeBranch(leg.getFlexBridge().getBranch());
            }
            result.setNormalSide(leg.getNormalSide());
            result.setFlipped(leg.isFlipped());
            result.setAmount(signedAmount(leg));
            result.setFinStatus(leg.getFinStatus());
            result.setFlexStatus(leg.getFlexStatus());
            result.setDriftStatus(leg.getDriftStatus());
            result.setReference(leg.getReference());
            result.setNote(leg.getNote() != null ? leg.getNote() : leg.getLastError());
            results.add(result);
        }
        return results;
    }

    /** Synapse names the bridge by its Fineract GL id; the report shows codes, as for the source. */
    private String glCode(Long glAccountId) {
        return glAccountId == null ? null : glAccounts.findById(glAccountId).map(GLAccount::getGlCode).orElse(null);
    }

    private static List<SynapseMirrorLeg> legs(SynapseMirrorRun view) {
        return view.getLegs() == null ? List.of() : view.getLegs();
    }

    /** Synapse sends a net-negative movement as a positive amount with {@code flipped}. */
    static BigDecimal signedAmount(SynapseMirrorLeg leg) {
        if (leg.getAmount() == null) {
            return null;
        }
        return leg.isFlipped() ? leg.getAmount().negate() : leg.getAmount();
    }

    private static BigDecimal amountTotal(List<SynapseMirrorLeg> legs) {
        BigDecimal total = BigDecimal.ZERO;
        for (SynapseMirrorLeg leg : legs) {
            BigDecimal signed = signedAmount(leg);
            if (signed != null) {
                total = total.add(signed);
            }
        }
        return total;
    }
}
