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
package org.apache.fineract.portfolio.savings.jobs.dormancygrace;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
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
 * Spring Batch configuration for the dormancy grace-window sweep (AB-550).
 * <p>
 * Only active when {@code fineract.synapse.enabled=true}: the revert is proposed to Synapse through the outbox, so
 * without Synapse there is nothing to propose to.
 * <p>
 * The job and step names must equal {@code JobName.EXPIRE_DORMANCY_GRACE_WINDOWS.name()} — the scheduler resolves the
 * bean from the enum constant, so a friendlier name here would make the job unrunnable.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fineract.synapse", name = "enabled", havingValue = "true")
public class ExpireDormancyGraceWindowsConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Bean
    public ExpireDormancyGraceWindowsTasklet expireDormancyGraceWindowsTasklet(
            SavingsAccountReadPlatformService savingsAccountReadPlatformService,
            SavingsAccountWritePlatformService savingsAccountWritePlatformService) {
        return new ExpireDormancyGraceWindowsTasklet(savingsAccountReadPlatformService, savingsAccountWritePlatformService);
    }

    @Bean
    protected Step expireDormancyGraceWindowsStep(ExpireDormancyGraceWindowsTasklet expireDormancyGraceWindowsTasklet) {
        return new StepBuilder(JobName.EXPIRE_DORMANCY_GRACE_WINDOWS.name(), jobRepository)
                .tasklet(expireDormancyGraceWindowsTasklet, transactionManager).build();
    }

    @Bean
    public Job expireDormancyGraceWindowsJob(ExpireDormancyGraceWindowsTasklet expireDormancyGraceWindowsTasklet) {
        return new JobBuilder(JobName.EXPIRE_DORMANCY_GRACE_WINDOWS.name(), jobRepository)
                .start(expireDormancyGraceWindowsStep(expireDormancyGraceWindowsTasklet)).incrementer(new RunIdIncrementer()).build();
    }
}
