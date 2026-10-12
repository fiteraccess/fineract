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
package org.apache.fineract.portfolio.eod.jobs;

import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/**
 * The EOD close-of-business job. Its one step runs outside a transaction: the chain polls Synapse for up to an hour per
 * waiting step and writes its own checkpoints, so a step-wide transaction would only hold a connection open.
 */
@Configuration
public class EodCloseOfBusinessConfig {

    @Bean
    protected Step eodCloseOfBusinessStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            EodCloseOfBusinessTasklet tasklet) {
        return new StepBuilder(JobName.EOD_CLOSE_OF_BUSINESS.name(), jobRepository).tasklet(tasklet, transactionManager)
                .transactionAttribute(new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED)).build();
    }

    @Bean
    public Job eodCloseOfBusinessJob(JobRepository jobRepository, Step eodCloseOfBusinessStep) {
        return new JobBuilder(JobName.EOD_CLOSE_OF_BUSINESS.name(), jobRepository).start(eodCloseOfBusinessStep)
                .incrementer(new RunIdIncrementer()).build();
    }
}
