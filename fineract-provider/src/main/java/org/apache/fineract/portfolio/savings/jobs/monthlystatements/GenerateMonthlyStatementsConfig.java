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
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
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
 * Spring Batch job configuration for the monthly customer statement run (AB-358).
 * <p>
 * Only active when {@code fineract.synapse.enabled=true}, since the work happens in Synapse.
 * <p>
 * The job and step names must equal {@code JobName.GENERATE_MONTHLY_STATEMENTS.name()} — the scheduler resolves the
 * bean from the enum constant, so a friendlier name here would make the job unrunnable.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fineract.synapse", name = "enabled", havingValue = "true")
public class GenerateMonthlyStatementsConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Bean
    public GenerateMonthlyStatementsTasklet generateMonthlyStatementsTasklet(SynapseTransactionClient synapseClient) {
        return new GenerateMonthlyStatementsTasklet(synapseClient);
    }

    @Bean
    protected Step generateMonthlyStatementsStep(GenerateMonthlyStatementsTasklet generateMonthlyStatementsTasklet) {
        return new StepBuilder(JobName.GENERATE_MONTHLY_STATEMENTS.name(), jobRepository)
                .tasklet(generateMonthlyStatementsTasklet, transactionManager).build();
    }

    @Bean
    public Job generateMonthlyStatementsJob(GenerateMonthlyStatementsTasklet generateMonthlyStatementsTasklet) {
        return new JobBuilder(JobName.GENERATE_MONTHLY_STATEMENTS.name(), jobRepository)
                .start(generateMonthlyStatementsStep(generateMonthlyStatementsTasklet)).incrementer(new RunIdIncrementer()).build();
    }
}
