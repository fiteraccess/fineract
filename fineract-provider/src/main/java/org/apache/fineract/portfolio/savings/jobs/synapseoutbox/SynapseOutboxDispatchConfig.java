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

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.cob.loan.ContextAwareTaskDecorator;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTaskHandler;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration for the Synapse outbox dispatcher.
 * <p>
 * Only active when {@code fineract.synapse.enabled=true}.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fineract.synapse", name = "enabled", havingValue = "true")
public class SynapseOutboxDispatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Bean("synapseOutboxExecutor")
    public ThreadPoolTaskExecutor synapseOutboxExecutor(FineractProperties fineractProperties) {
        int poolSize = fineractProperties.getSynapse().getOutboxThreadPoolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setThreadNamePrefix("SynapseOutbox-");
        executor.setTaskDecorator(new ContextAwareTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean
    public SynapseOutboxTasklet synapseOutboxTasklet(SynapseOutboxRepository outboxRepository, List<SynapseTaskHandler> handlers,
            CircuitBreakerRegistry circuitBreakerRegistry, FineractProperties fineractProperties,
            ThreadPoolTaskExecutor synapseOutboxExecutor) {
        return new SynapseOutboxTasklet(outboxRepository, handlers, circuitBreakerRegistry, fineractProperties, synapseOutboxExecutor);
    }

    @Bean
    protected Step dispatchSynapseOutboxStep(SynapseOutboxTasklet synapseOutboxTasklet) {
        return new StepBuilder(JobName.DISPATCH_SYNAPSE_OUTBOX.name(), jobRepository).tasklet(synapseOutboxTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job dispatchSynapseOutboxJob(SynapseOutboxTasklet synapseOutboxTasklet) {
        return new JobBuilder(JobName.DISPATCH_SYNAPSE_OUTBOX.name(), jobRepository).start(dispatchSynapseOutboxStep(synapseOutboxTasklet))
                .incrementer(new RunIdIncrementer()).build();
    }
}
