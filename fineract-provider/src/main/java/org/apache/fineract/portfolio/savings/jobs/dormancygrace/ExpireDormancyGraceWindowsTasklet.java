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

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * AB-550: reverts accounts whose 24-hour reactivation grace window elapsed without a qualifying transaction.
 *
 * <p>
 * This only ever <i>proposes</i> the revert. Each account becomes an ordinary DORMANCY_STATUS outbox row, and Synapse
 * re-checks its own grace record before applying: it observes a customer transaction before Fineract does, so an
 * account selected here may legitimately be rejected as already satisfied. A rejection is the system working, not a
 * failure — which is why this tasklet does not treat one as an error.
 */
@Slf4j
@RequiredArgsConstructor
public class ExpireDormancyGraceWindowsTasklet implements Tasklet {

    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        final LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
        final List<Long> lapsed = savingsAccountReadPlatformService.retrieveSavingsIdsWithLapsedDormancyGrace(now);
        if (lapsed == null || lapsed.isEmpty()) {
            return RepeatStatus.FINISHED;
        }
        for (Long savingsId : lapsed) {
            savingsAccountWritePlatformService.revertLapsedDormancyGrace(savingsId);
        }
        log.info("Dormancy grace windows lapsed: proposed {} reverts to Synapse", lapsed.size());
        return RepeatStatus.FINISHED;
    }
}
