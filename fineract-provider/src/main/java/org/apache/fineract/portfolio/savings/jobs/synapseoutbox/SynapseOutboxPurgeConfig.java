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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration for the Synapse outbox purge.
 * <p>
 * Only active when {@code fineract.synapse.enabled=true}.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fineract.synapse", name = "enabled", havingValue = "true")
public class SynapseOutboxPurgeConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Bean
    public SynapseOutboxPurgeTasklet synapseOutboxPurgeTasklet(SynapseOutboxRepository outboxRepository,
            FineractProperties fineractProperties) {
        return new SynapseOutboxPurgeTasklet(outboxRepository, fineractProperties);
    }

    @Bean
    protected Step purgeSynapseOutboxStep(SynapseOutboxPurgeTasklet synapseOutboxPurgeTasklet) {
        return new StepBuilder(JobName.PURGE_SYNAPSE_OUTBOX.name(), jobRepository)
                .tasklet(synapseOutboxPurgeTasklet, transactionManager).build();
    }

    @Bean
    public Job purgeSynapseOutboxJob(SynapseOutboxPurgeTasklet synapseOutboxPurgeTasklet) {
        return new JobBuilder(JobName.PURGE_SYNAPSE_OUTBOX.name(), jobRepository)
                .start(purgeSynapseOutboxStep(synapseOutboxPurgeTasklet))
                .incrementer(new RunIdIncrementer()).build();
    }
}
