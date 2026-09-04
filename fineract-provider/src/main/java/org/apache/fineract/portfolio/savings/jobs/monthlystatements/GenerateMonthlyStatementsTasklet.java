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
package org.apache.fineract.portfolio.savings.jobs.monthlystatements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseMonthlyStatementPlanResponse;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * AB-358 (R-D-24): asks Synapse to enqueue the previous month's customer statements.
 *
 * <p>
 * Unlike the other Synapse-related jobs, the work itself does not happen here — Synapse owns the statement documents,
 * their retry state and their delivery. This tasklet exists so that the run has a schedule Quartz controls and an entry
 * in the web-app's Manage Jobs page, which is what lets an operator see the last run and re-run a month by hand.
 *
 * <p>
 * <b>Success means enqueued, not delivered.</b> Rendering and emailing happen afterwards in Synapse's own worker, and
 * are reported by {@code m_statement_document.status} rather than by this job's history. Blocking until delivery
 * finished is not an option: a full run takes hours and the client read timeout is 30 seconds, so the job would record
 * a failure on every real run while the work carried on regardless.
 *
 * <p>
 * Re-running is safe. Synapse's unique index over account and period makes a second run for the same month enqueue
 * nothing.
 */
@Slf4j
@RequiredArgsConstructor
public class GenerateMonthlyStatementsTasklet implements Tasklet {

    private final SynapseTransactionClient synapseClient;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SynapseMonthlyStatementPlanResponse response = synapseClient.postMonthlyStatementPlan();
        if (response == null) {
            // A 2xx with no body means the request was accepted but we cannot say what it enqueued. Treat it
            // as a failure rather than logging a misleading count — the run is idempotent, so retrying costs
            // one wasted sweep and nothing worse.
            throw new IllegalStateException("Synapse accepted the monthly statement plan but returned no body");
        }
        log.info("Monthly statement run enqueued {} account(s) for {} to {}", response.getEnqueued(), response.getPeriodFrom(),
                response.getPeriodTo());
        return RepeatStatus.FINISHED;
    }
}
