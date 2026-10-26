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
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodGlResult;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseZeroCheck;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseZeroCheckResult;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step 7, the zero-balance check: once every mirror job completed, Synapse checks that each bridge pair, settlement
 * bridge and the CBN mirror GL nets to what it should. Every check not CLEAR (a FAIL, or an UNVERIFIED balance that
 * could not be read) is a CRITICAL exception row and fails the step, so the day cannot be signed off.
 */
@Component
public class EodZeroBalanceStep extends AbstractEodStep {

    public static final String NAME = "EOD_ZERO_BALANCE";
    public static final String CODE_ZERO_BALANCE_REFUSED = "EOD-ZERO-BALANCE-REFUSED";
    public static final String CODE_ZERO_BALANCE_FAIL = "EOD-ZERO-BALANCE-FAIL";
    public static final List<String> MIRROR_STEPS = List.of(EodMirrorDepositStep.NAME, EodMirrorIncomeStep.NAME, EodMirrorTaxStep.NAME,
            EodMirrorInterestStep.NAME);

    private final ObjectProvider<SynapseTransactionClient> synapseClient;

    public EodZeroBalanceStep(EodRunService runService, FineractProperties properties,
            ObjectProvider<SynapseTransactionClient> synapseClient) {
        super(runService, properties);
        this.synapseClient = synapseClient;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        for (String mirror : MIRROR_STEPS) {
            requirePrerequisite(run, mirror);
        }
        SynapseTransactionClient client = requireSynapse(synapseClient);
        AtomicReference<SynapseZeroCheck> answer = new AtomicReference<>();
        // Synapse computes the checks in the request and a repeat is idempotent, so an outage is simply asked again.
        Map<String, Object> polled = waitUntil(step, () -> {
            try {
                answer.set(client.runZeroCheck(run.getBusinessDate()));
            } catch (SynapsePostingException e) {
                if (isRefusal(e)) {
                    throw new EodStepFailedException(CODE_ZERO_BALANCE_REFUSED, e.getMessage(), e);
                }
                throw e;
            }
            return Poll.done(Map.of());
        });
        SynapseZeroCheck zeroCheck = answer.get();
        List<SynapseZeroCheckResult> checks = zeroCheck.getChecks() == null ? List.of() : zeroCheck.getChecks();
        runService.replaceGlResults(run, NAME, results(checks));
        List<String> notClear = new ArrayList<>();
        int fail = 0;
        int unverified = 0;
        for (SynapseZeroCheckResult check : checks) {
            if (SynapseZeroCheckResult.RESULT_CLEAR.equals(check.getResult())) {
                continue;
            }
            if (SynapseZeroCheckResult.RESULT_UNVERIFIED.equals(check.getResult())) {
                unverified++;
            } else {
                fail++;
            }
            notClear.add(check.getName() + " " + check.getResult());
            runService.recordException(run, NAME, EodException.SEVERITY_CRITICAL, CODE_ZERO_BALANCE_FAIL, message(check),
                    check.getKind() + ":" + check.getGlCode() + ":" + check.getSide());
        }
        String status = zeroCheck.getRun() == null ? null : zeroCheck.getRun().getStatus();
        if (!notClear.isEmpty() || !SynapseZeroCheck.STATUS_CLEAR.equals(status)) {
            throw new EodStepFailedException(CODE_ZERO_BALANCE_FAIL, "zero-balance checks for " + run.getBusinessDate() + " are " + status
                    + ": " + fail + " failed, " + unverified + " unverified " + notClear);
        }
        return detail("status", status, "checks", checks.size(), "clear", checks.size(), "fail", 0, "unverified", 0, "polls",
                polled.get("polls"));
    }

    private static String message(SynapseZeroCheckResult check) {
        BigDecimal shortfall = check.getExpected() == null || check.getActual() == null ? null
                : check.getExpected().subtract(check.getActual());
        return check.getResult() + " " + check.getName() + ": gl=" + check.getGlCode()
                + (check.getBranch() == null ? "" : " branch=" + check.getBranch()) + " side=" + check.getSide() + " expected="
                + plain(check.getExpected()) + " actual=" + plain(check.getActual()) + " shortfall=" + plain(shortfall)
                + (check.getNote() == null ? "" : " (" + check.getNote() + ")");
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "n/a" : amount.toPlainString();
    }

    private static List<EodGlResult> results(List<SynapseZeroCheckResult> checks) {
        List<EodGlResult> results = new ArrayList<>(checks.size());
        for (SynapseZeroCheckResult check : checks) {
            EodGlResult result = EodGlResult.of(check.getKind(), check.getCheckNo(), check.getName());
            result.setSide(check.getSide());
            if (SynapseZeroCheckResult.SIDE_FLEXCUBE.equals(check.getSide())) {
                result.setFlexBridgeGl(check.getGlCode());
                result.setFlexBridgeBranch(check.getBranch());
            } else {
                result.setFinBridgeGl(check.getGlCode());
            }
            result.setExpected(check.getExpected());
            result.setActual(check.getActual());
            result.setCheckResult(check.getResult());
            result.setNote(check.getNote());
            results.add(result);
        }
        return results;
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD zero balance: confirm every bridge and mirror GL nets to zero after the mirror";
    }
}
