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
package org.apache.fineract.portfolio.savings.jobs.synapseoutbox;

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Purges old SENT entries from the {@code synapse_outbox} table based on the configured retention period.
 */
@Slf4j
public class SynapseOutboxPurgeTasklet implements Tasklet {

    private final SynapseOutboxRepository outboxRepository;
    private final int retentionDays;

    public SynapseOutboxPurgeTasklet(SynapseOutboxRepository outboxRepository, FineractProperties fineractProperties) {
        this.outboxRepository = outboxRepository;
        this.retentionDays = fineractProperties.getSynapse().getOutboxRetentionDays();
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        outboxRepository.purgeOldSentEntries(retentionDays);
        return RepeatStatus.FINISHED;
    }
}
