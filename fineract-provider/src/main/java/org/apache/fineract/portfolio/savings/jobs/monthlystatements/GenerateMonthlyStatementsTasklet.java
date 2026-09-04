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
 * AB-358 (R-D-24): asks Synapse to start the previous month's customer statement run.
 *
 * <p>
 * Unlike the other Synapse-related jobs, the work itself does not happen here — Synapse owns the statement documents,
 * their retry state and their delivery. This tasklet exists so that the run has a schedule Quartz controls and an entry
 * in the web-app's Manage Jobs page, which is what lets an operator see the last run and re-run a month by hand.
 *
 * <p>
 * <b>Success means the run was accepted — not enqueued, and not delivered.</b> Synapse selects the accounts on a
 * background planner and renders and emails them on its own workers. Neither stage can be waited for here: selecting
 * millions of accounts takes minutes and delivering them takes hours, against a 30-second client read timeout, so a
 * blocking job would record a failure on every real run while the work carried on regardless. Progress is visible in
 * Synapse's logs and metrics, and per-account outcomes in {@code m_statement_document.status}.
 *
 * <p>
 * Re-running is safe, and is also how a run interrupted by a restart is resumed: Synapse ignores a request for a month
 * it is already planning, and its unique index over account and period means re-selecting an account enqueues nothing.
 */
@Slf4j
@RequiredArgsConstructor
public class GenerateMonthlyStatementsTasklet implements Tasklet {

    private final SynapseTransactionClient synapseClient;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SynapseMonthlyStatementPlanResponse response = synapseClient.postMonthlyStatementPlan();
        if (response == null) {
            // A 2xx with no body means we cannot say which month was accepted, or whether anything was. Treat it
            // as a failure rather than logging a misleading success — the run is idempotent, so retrying costs
            // one wasted sweep and nothing worse.
            throw new IllegalStateException("Synapse accepted the monthly statement plan but returned no body");
        }
        if (Boolean.FALSE.equals(response.getStarted())) {
            log.info("Monthly statement run for {} to {} was already in progress; nothing further started", response.getPeriodFrom(),
                    response.getPeriodTo());
        } else {
            log.info("Monthly statement run started for {} to {}; Synapse reports the account count and delivery", response.getPeriodFrom(),
                    response.getPeriodTo());
        }
        return RepeatStatus.FINISHED;
    }
}
