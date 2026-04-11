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

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTaskHandler;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Fineract scheduled job tasklet that drains the {@code synapse_outbox} table.
 * <p>
 * For each registered {@link SynapseTaskHandler}, claims a page of PENDING rows
 * and dispatches them through the handler, respecting the circuit breaker state.
 */
@Slf4j
public class SynapseOutboxTasklet implements Tasklet {

    private final SynapseOutboxRepository outboxRepository;
    private final List<SynapseTaskHandler> handlers;
    private final CircuitBreaker circuitBreaker;
    private final int pageSize;

    public SynapseOutboxTasklet(SynapseOutboxRepository outboxRepository, List<SynapseTaskHandler> handlers,
            CircuitBreakerRegistry circuitBreakerRegistry, FineractProperties fineractProperties) {
        this.outboxRepository = outboxRepository;
        this.handlers = handlers;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("synapseOutbox");
        this.pageSize = fineractProperties.getSynapse().getOutboxPageSize();
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        if (handlers.isEmpty()) {
            log.debug("No SynapseTaskHandler beans registered, nothing to drain");
            return RepeatStatus.FINISHED;
        }
        for (SynapseTaskHandler handler : handlers) {
            drainTaskType(handler);
        }
        return RepeatStatus.FINISHED;
    }

    private void drainTaskType(SynapseTaskHandler handler) {
        String taskType = handler.taskType();
        log.debug("Draining outbox for taskType={}", taskType);

        boolean hasMore = true;
        while (hasMore) {
            List<OutboxEntry> batch = outboxRepository.claimPending(taskType, pageSize);
            if (batch.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (int i = 0; i < batch.size(); i++) {
                OutboxEntry entry = batch.get(i);
                try {
                    circuitBreaker.executeRunnable(() -> handler.dispatch(entry));
                    outboxRepository.markSent(List.of(entry.getId()));
                } catch (CallNotPermittedException e) {
                    log.warn("Circuit breaker OPEN for taskType={}, resetting remaining entries to PENDING.", taskType);
                    List<Long> remainingIds = batch.subList(i, batch.size()).stream()
                            .map(OutboxEntry::getId).toList();
                    outboxRepository.resetToPending(remainingIds);
                    hasMore = false;
                    break;
                } catch (SynapsePostingException e) {
                    log.error("Synapse posting failed for entry id={}: {}", entry.getId(), e.getMessage());
                    outboxRepository.markFailed(entry.getId(), truncate(e.getMessage()));
                } catch (Exception e) {
                    log.error("Unexpected error dispatching entry id={}: {}", entry.getId(), e.getMessage(), e);
                    outboxRepository.markFailed(entry.getId(), truncate(e.getClass().getName() + ": " + e.getMessage()));
                }
            }
        }
    }

    private static final int MAX_ERROR_DETAIL_LENGTH = 1000;

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_DETAIL_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_DETAIL_LENGTH) + "…[truncated]";
    }
}
