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
package org.apache.fineract.portfolio.savings.jobs.updatesavingsdormantaccounts;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * AB-550: Access Bank runs a single-rung lifecycle — NONE to DORMANT, and DORMANT is terminal until an explicit
 * reactivation. The INACTIVE and ESCHEAT passes are retired here rather than only disabled by clearing their product
 * thresholds, because a threshold cleared in config can be set again from the product screen and would silently resume
 * escheating balances.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class UpdateSavingsDormantAccountsTasklet implements Tasklet {

    private final SavingsAccountReadPlatformService savingAccountReadPlatformService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        LocalDate tenantLocalDate = DateUtils.getBusinessLocalDate();

        List<Long> savingsPendingDormant = savingAccountReadPlatformService.retrieveSavingsIdsPendingDormant(tenantLocalDate);
        if (null != savingsPendingDormant && savingsPendingDormant.size() > 0) {
            for (Long savingsId : savingsPendingDormant) {
                savingsAccountWritePlatformService.setSubStatusDormant(savingsId);
            }
        }
        return RepeatStatus.FINISHED;
    }
}
