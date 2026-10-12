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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseReplayStatus;
import org.apache.fineract.portfolio.savings.jobs.postinterestforsavings.PostInterestForSavingsService;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step 2: post savings interest every night, as Fineract's own posting job did, with D (the COB date the steps run
 * under) as the cut-off; each product's posting period decides what is due, so most nights nothing is. When this run
 * wrote instructions to the Synapse outbox, wait until the money has actually moved: every one is SENT and Synapse
 * reports D's interest and withholding-tax rows landed in Fineract. A DEAD interest entry holds that account's later
 * entries in the outbox, so any one, however old, stops the step before it posts.
 */
@Component
public class EodInterestPostingStep extends AbstractEodStep {

    public static final String NAME = "EOD_INTEREST_POSTING";
    public static final String CODE_POSTING = "EOD-POSTING";
    public static final String CODE_POSTING_DEAD = "EOD-POSTING-DEAD";
    public static final String CODE_POSTING_BLOCKED = "EOD-POSTING-BLOCKED";
    public static final String CODE_POSTING_REPLAY_FAILED = "EOD-POSTING-REPLAY-FAILED";
    static final String TASK_TYPE = "INTEREST_POSTING";
    static final int DEAD_ACCOUNTS_SHOWN = 20;

    private final PostInterestForSavingsService postingService;
    private final ObjectProvider<SynapseOutboxRepository> outbox;
    private final ObjectProvider<SynapseTransactionClient> synapseClient;

    public EodInterestPostingStep(EodRunService runService, FineractProperties properties, PostInterestForSavingsService postingService,
            ObjectProvider<SynapseOutboxRepository> outbox, ObjectProvider<SynapseTransactionClient> synapseClient) {
        super(runService, properties);
        this.postingService = postingService;
        this.outbox = outbox;
        this.synapseClient = synapseClient;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        requirePrerequisite(run, EodAccrualStep.NAME);
        LocalDate closing = run.getBusinessDate();
        SynapseOutboxRepository outboxRepository = outbox.getIfAvailable();
        SynapseTransactionClient client = requireSynapse(synapseClient);
        if (outboxRepository == null) {
            throw new EodStepFailedException(EodRolloverStep.CODE_SYNAPSE_DISABLED, "the Synapse outbox is not enabled");
        }
        Instant since = outboxBoundary(run);
        requireNoDeadEntries(outboxRepository);
        try {
            postingService.postInterest(properties.getEod().getPostingThreadPoolSize(), properties.getEod().getPostingBatchSize());
        } catch (JobExecutionException e) {
            throw new EodStepFailedException(CODE_POSTING, "interest posting for " + closing + " failed: " + e.getMessage(), e);
        }
        if (outboxRepository.countByStatusSince(TASK_TYPE, since).isEmpty()) {
            return detail("posted", 0, "note", "no interest due on " + closing);
        }
        return waitUntil(step, () -> {
            Map<String, Long> outboxCounts = outboxRepository.countByStatusSince(TASK_TYPE, since);
            long dead = outboxRepository.countDead(TASK_TYPE);
            if (dead > 0) {
                throw new EodStepFailedException(CODE_POSTING_DEAD, dead + " interest instructions went DEAD in the Synapse outbox");
            }
            long queued = outboxCounts.getOrDefault("PENDING", 0L) + outboxCounts.getOrDefault("DISPATCHED", 0L);
            SynapseReplayStatus status = client.getReplayStatus(closing);
            long failed = replayCount(status, "FAILED_POST_TO_FINERACT") + replayCount(status, "UNKNOWN") + replayCount(status, "FAILED");
            if (failed > 0) {
                throw new EodStepFailedException(CODE_POSTING_REPLAY_FAILED,
                        failed + " interest rows dated " + closing + " failed to reach Fineract");
            }
            long notLanded = replayCount(status, "PENDING") + replayCount(status, "POSTED_TO_TB");
            Map<String, Object> seen = detail("outboxQueued", queued, "outboxSent", outboxCounts.getOrDefault("SENT", 0L),
                    "replayNotLanded", notLanded, "replayLanded", replayCount(status, "POSTED_TO_FINERACT"));
            return queued == 0 && notLanded == 0 ? Poll.done(seen) : Poll.waiting(seen);
        });
    }

    private void requireNoDeadEntries(SynapseOutboxRepository outboxRepository) {
        long dead = outboxRepository.countDead(TASK_TYPE);
        if (dead > 0) {
            throw new EodStepFailedException(CODE_POSTING_BLOCKED,
                    dead + " DEAD interest instruction(s) in the Synapse outbox hold their accounts' later entries; retry them"
                            + " through the outbox admin before closing the day. Accounts: "
                            + outboxRepository.findDeadAccountIds(TASK_TYPE, DEAD_ACCOUNTS_SHOWN));
        }
    }

    /**
     * The run's rollover completion: fixed once per run and before any posting, so a retried step still counts the
     * first attempt's entries. Step times are tenant-local; outbox rows are stamped from an Instant.
     */
    private Instant outboxBoundary(EodRun run) {
        LocalDateTime rolledAt = runService.findStep(run, EodRolloverStep.NAME).map(EodStep::getCompletedAt)
                .orElseThrow(() -> new EodStepFailedException(CODE_PREREQUISITE,
                        NAME + " requires " + EodRolloverStep.NAME + " to be COMPLETED for " + run.getBusinessDate()));
        return rolledAt.atZone(DateUtils.getDateTimeZoneOfTenant()).toInstant();
    }

    private static long replayCount(SynapseReplayStatus status, String rowStatus) {
        if (status.getInterestRows() == null) {
            return 0;
        }
        long count = 0;
        for (Map<String, Long> byStatus : status.getInterestRows().values()) {
            count += byStatus.getOrDefault(rowStatus, 0L);
        }
        return count;
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD interest posting: post the savings interest due on the closing day and wait until it has landed";
    }
}
