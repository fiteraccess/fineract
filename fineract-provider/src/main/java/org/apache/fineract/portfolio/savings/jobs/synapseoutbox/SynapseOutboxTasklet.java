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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
    private final ThreadPoolTaskExecutor executor;
    private final int threadPoolSize;
    private final int staleDispatchedMinutes;

    public SynapseOutboxTasklet(SynapseOutboxRepository outboxRepository, List<SynapseTaskHandler> handlers,
            CircuitBreakerRegistry circuitBreakerRegistry, FineractProperties fineractProperties,
            ThreadPoolTaskExecutor executor) {
        this.outboxRepository = outboxRepository;
        this.handlers = handlers;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("synapseOutbox");
        this.pageSize = fineractProperties.getSynapse().getOutboxPageSize();
        this.executor = executor;
        this.threadPoolSize = fineractProperties.getSynapse().getOutboxThreadPoolSize();
        this.staleDispatchedMinutes = fineractProperties.getSynapse().getOutboxStaleDispatchedMinutes();
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        if (handlers.isEmpty()) {
            log.debug("No SynapseTaskHandler beans registered, nothing to drain");
            return RepeatStatus.FINISHED;
        }

        outboxRepository.reclaimStaleDispatched(staleDispatchedMinutes);

        List<CompletableFuture<DrainResult>> futures = new ArrayList<>(threadPoolSize);
        for (int i = 0; i < threadPoolSize; i++) {
            SynapseTaskHandler handler = handlers.get(i % handlers.size());
            futures.add(CompletableFuture.supplyAsync(() -> drainTaskType(handler), executor));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long totalSent = 0;
        long totalFailed = 0;
        long totalReset = 0;
        for (CompletableFuture<DrainResult> f : futures) {
            DrainResult r = f.join();
            totalSent += r.sent();
            totalFailed += r.failed();
            totalReset += r.reset();
        }

        log.info("Synapse Outbox drain complete: sent={}, failed={}, reset={}", totalSent, totalFailed, totalReset);
        log.info("Synapse Outbox Stats: {}", outboxRepository.getOutboxStats());
        return RepeatStatus.FINISHED;
    }

    /**
     * Drains all pending outbox entries for a single handler's task type.
     * Claims a page, dispatches each entry, repeats until no more work.
     * Stale DISPATCHED rows from crashed runs are reclaimed at the start of execute().
     */
    private DrainResult drainTaskType(SynapseTaskHandler handler) {
        String taskType = handler.taskType();
        log.debug("Worker [{}] draining outbox for taskType={}", Thread.currentThread().getName(), taskType);

        long sent = 0;
        long failed = 0;
        long reset = 0;

        while (true) {
            List<OutboxEntry> batch = outboxRepository.claimPending(taskType, pageSize);
            if (batch.isEmpty()) {
                break;
            }

            for (int i = 0; i < batch.size(); i++) {
                OutboxEntry entry = batch.get(i);
                try {
                    circuitBreaker.executeRunnable(() -> handler.dispatch(entry));
                    outboxRepository.markSent(List.of(entry.getId()));
                    sent++;
                } catch (CallNotPermittedException e) {
                    log.warn("Circuit breaker OPEN for taskType={}, resetting remaining entries to PENDING.", taskType);
                    List<Long> remainingIds = batch.subList(i, batch.size()).stream().map(OutboxEntry::getId).toList();
                    outboxRepository.resetToPending(remainingIds);
                    reset += remainingIds.size();
                    return new DrainResult(sent, failed, reset);
                } catch (SynapsePostingException e) {
                    log.error("Synapse posting failed for entry id={}: {}", entry.getId(), e.getMessage());
                    outboxRepository.markFailed(entry.getId(), truncate(e.getMessage()), entry.getAttempts(), entry.getMaxAttempts());
                    failed++;
                } catch (Exception e) {
                    log.error("Unexpected error dispatching entry id={}: {}", entry.getId(), e.getMessage(), e);
                    outboxRepository.markFailed(entry.getId(), truncate(e.getClass().getName() + ": " + e.getMessage()),
                            entry.getAttempts(), entry.getMaxAttempts());
                    failed++;
                }
            }
        }

        return new DrainResult(sent, failed, reset);
    }

    private static final int MAX_ERROR_DETAIL_LENGTH = 1000;

    static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_DETAIL_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_DETAIL_LENGTH) + "…[truncated]";
    }

    record DrainResult(long sent, long failed, long reset) {}
}
