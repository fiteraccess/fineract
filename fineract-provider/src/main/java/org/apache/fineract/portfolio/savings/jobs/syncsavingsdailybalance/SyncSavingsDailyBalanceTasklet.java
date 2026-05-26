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
package org.apache.fineract.portfolio.savings.jobs.syncsavingsdailybalance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService;
import org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService.SyncResult;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SyncSavingsDailyBalanceTasklet implements Tasklet {

    private final SavingsDailyBalanceSyncService syncService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        long start = System.currentTimeMillis();
        SyncResult result = syncService.syncNow();
        long elapsedMs = System.currentTimeMillis() - start;
        log.info("Savings daily balance sync: upserted={}, drained={}, from={}, to={}, elapsedMs={}", result.upserted(), result.drained(),
                result.from(), result.to(), elapsedMs);
        return RepeatStatus.FINISHED;
    }
}
