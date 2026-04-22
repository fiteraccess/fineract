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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.cob.loan.ContextAwareTaskDecorator;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSynapseProperties;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTaskHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@ExtendWith(MockitoExtension.class)
class SynapseOutboxTaskletTest {

    @Mock
    private SynapseOutboxRepository outboxRepository;

    @Mock
    private SynapseTaskHandler handler;

    private static final int PAGE_SIZE = 100;

    private SynapseOutboxTasklet createTasklet(List<SynapseTaskHandler> handlers) {
        return createTasklet(handlers, CircuitBreakerRegistry.ofDefaults());
    }

    private SynapseOutboxTasklet createTasklet(List<SynapseTaskHandler> handlers, CircuitBreakerRegistry registry) {
        return createTasklet(handlers, registry, 1);
    }

    private SynapseOutboxTasklet createTasklet(List<SynapseTaskHandler> handlers, CircuitBreakerRegistry registry, int poolSize) {
        FineractProperties props = new FineractProperties();
        FineractSynapseProperties synapse = new FineractSynapseProperties();
        synapse.setOutboxPageSize(PAGE_SIZE);
        synapse.setOutboxThreadPoolSize(poolSize);
        props.setSynapse(synapse);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.initialize();
        return new SynapseOutboxTasklet(outboxRepository, handlers, registry, props, executor);
    }

    private static OutboxEntry entry(long id) {
        return OutboxEntry.builder().id(id).taskType("INTEREST_POSTING").build();
    }

    private static OutboxEntry entry(long id, String taskType) {
        return OutboxEntry.builder().id(id).taskType(taskType).build();
    }

    @Nested
    class Execute {

        @org.junit.jupiter.api.BeforeEach
        void stubStats() {
            org.mockito.Mockito.lenient().when(outboxRepository.getOutboxStats()).thenReturn(Map.of());
        }

        @Test
        void execute_noHandlers_returnsFinished() throws Exception {
            SynapseOutboxTasklet tasklet = createTasklet(Collections.emptyList());

            RepeatStatus status = tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            verifyNoInteractions(outboxRepository);
        }

        @Test
        void execute_noPendingRows_returnsFinished() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE)).thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            RepeatStatus status = tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            verify(outboxRepository, never()).markSent(any());
        }

        @Test
        void execute_allEntriesDispatched_marksAllSent() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            List<OutboxEntry> entries = List.of(entry(1L), entry(2L), entry(3L));
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE))
                    .thenReturn(entries)
                    .thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).markSent(List.of(2L));
            verify(outboxRepository).markSent(List.of(3L));
        }

        @Test
        void execute_partialFailure_marksSuccessAndFailureSeparately() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            OutboxEntry e1 = entry(1L);
            OutboxEntry e2 = entry(2L);
            OutboxEntry e3 = entry(3L);
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE))
                    .thenReturn(List.of(e1, e2, e3))
                    .thenReturn(Collections.emptyList());

            doNothing()
                    .doThrow(new SynapsePostingException("posting failed"))
                    .doNothing()
                    .when(handler).dispatch(any());
            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).markFailed(eq(2L), eq("posting failed"), eq(e2.getAttempts()), eq(e2.getMaxAttempts()));
            verify(outboxRepository).markSent(List.of(3L));
        }

        @Test
        void execute_circuitBreakerOpen_resetsRemainingToPending() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            List<OutboxEntry> entries = List.of(entry(1L), entry(2L), entry(3L));
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE)).thenReturn(entries);

            CircuitBreaker mockCb = mock(CircuitBreaker.class);
            CircuitBreakerRegistry mockRegistry = mock(CircuitBreakerRegistry.class);
            when(mockRegistry.circuitBreaker("synapseOutbox")).thenReturn(mockCb);

            // First call succeeds, second throws CallNotPermittedException
            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).doThrow(mock(CallNotPermittedException.class))
                    .when(mockCb).executeRunnable(any());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler), mockRegistry);
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).resetToPending(List.of(2L, 3L));
            verify(outboxRepository, never()).markFailed(any(), anyString(), anyInt(), anyInt());
        }

        @Test
        void execute_unexpectedException_marksFailed() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            OutboxEntry e1 = entry(1L);
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE))
                    .thenReturn(List.of(e1))
                    .thenReturn(Collections.emptyList());

            doThrow(new RuntimeException("something broke")).when(handler).dispatch(any());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).markFailed(eq(1L), eq("java.lang.RuntimeException: something broke"), eq(e1.getAttempts()),
                    eq(e1.getMaxAttempts()));
            verify(outboxRepository, never()).markSent(any());
        }

        @Test
        void execute_drainsMultiplePages() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            List<OutboxEntry> page1 = List.of(entry(1L), entry(2L));
            List<OutboxEntry> page2 = List.of(entry(3L));
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE))
                    .thenReturn(page1)
                    .thenReturn(page2)
                    .thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).markSent(List.of(2L));
            verify(outboxRepository).markSent(List.of(3L));
        }

        @Test
        void execute_circuitBreakerOpen_stopsProcessingTaskType() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            List<OutboxEntry> entries = List.of(entry(1L), entry(2L));
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE)).thenReturn(entries);

            CircuitBreaker mockCb = mock(CircuitBreaker.class);
            CircuitBreakerRegistry mockRegistry = mock(CircuitBreakerRegistry.class);
            when(mockRegistry.circuitBreaker("synapseOutbox")).thenReturn(mockCb);

            doThrow(mock(CallNotPermittedException.class)).when(mockCb).executeRunnable(any());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler), mockRegistry);
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).claimPending("INTEREST_POSTING", PAGE_SIZE);
            verify(outboxRepository).resetToPending(List.of(1L, 2L));
            verify(outboxRepository, never()).markSent(any());
        }
    }

    @Nested
    class FairExecution {

        @org.junit.jupiter.api.BeforeEach
        void stubStats() {
            org.mockito.Mockito.lenient().when(outboxRepository.getOutboxStats()).thenReturn(Map.of());
        }

        @Test
        void execute_multipleHandlers_distributesWorkersRoundRobin() throws Exception {
            SynapseTaskHandler handlerA = mock(SynapseTaskHandler.class);
            SynapseTaskHandler handlerB = mock(SynapseTaskHandler.class);
            when(handlerA.taskType()).thenReturn("TYPE_A");
            when(handlerB.taskType()).thenReturn("TYPE_B");

            when(outboxRepository.claimPending(eq("TYPE_A"), eq(PAGE_SIZE))).thenReturn(Collections.emptyList());
            when(outboxRepository.claimPending(eq("TYPE_B"), eq(PAGE_SIZE))).thenReturn(Collections.emptyList());

            // pool size = 4, 2 handlers → round-robin: A, B, A, B
            SynapseOutboxTasklet tasklet = createTasklet(List.of(handlerA, handlerB), CircuitBreakerRegistry.ofDefaults(), 4);
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository, atLeastOnce()).claimPending("TYPE_A", PAGE_SIZE);
            verify(outboxRepository, atLeastOnce()).claimPending("TYPE_B", PAGE_SIZE);
        }

        @Test
        void execute_poolSizeOne_gracefulSingleThreaded() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            List<OutboxEntry> entries = List.of(entry(1L));
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE))
                    .thenReturn(entries)
                    .thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler), CircuitBreakerRegistry.ofDefaults(), 1);
            RepeatStatus status = tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            verify(outboxRepository).markSent(List.of(1L));
        }
    }

    @Nested
    class StaleReclaim {

        @org.junit.jupiter.api.BeforeEach
        void stubStats() {
            org.mockito.Mockito.lenient().when(outboxRepository.getOutboxStats()).thenReturn(Map.of());
        }

        @Test
        void execute_reclaimsStaleDispatchedBeforeSpawningWorkers() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE)).thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            verify(outboxRepository).reclaimStaleDispatched(anyInt());
        }
    }

    @Nested
    class WaitForAllThreads {

        @org.junit.jupiter.api.BeforeEach
        void stubStats() {
            org.mockito.Mockito.lenient().when(outboxRepository.getOutboxStats()).thenReturn(Map.of());
        }

        @Test
        void execute_waitsForAllWorkersBeforeReturning() throws Exception {
            SynapseTaskHandler handlerA = mock(SynapseTaskHandler.class);
            SynapseTaskHandler handlerB = mock(SynapseTaskHandler.class);
            when(handlerA.taskType()).thenReturn("TYPE_A");
            when(handlerB.taskType()).thenReturn("TYPE_B");

            AtomicInteger completedWorkers = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(2);

            when(outboxRepository.claimPending(eq("TYPE_A"), eq(PAGE_SIZE))).thenAnswer(inv -> {
                startLatch.countDown();
                assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
                completedWorkers.incrementAndGet();
                return Collections.emptyList();
            });
            when(outboxRepository.claimPending(eq("TYPE_B"), eq(PAGE_SIZE))).thenAnswer(inv -> {
                startLatch.countDown();
                assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
                completedWorkers.incrementAndGet();
                return Collections.emptyList();
            });

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handlerA, handlerB), CircuitBreakerRegistry.ofDefaults(), 2);
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            // By the time execute returns, both workers must have completed
            assertThat(completedWorkers.get()).isEqualTo(2);
        }
    }

    @Nested
    class ContextPropagation {

        private ThreadPoolTaskExecutor decoratedExecutor;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
            ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
            ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 12))));
            ThreadLocalContextUtil.setAuthToken("test-token");
            ThreadLocalContextUtil.setDataSourceContext("tenants");

            decoratedExecutor = new ThreadPoolTaskExecutor();
            decoratedExecutor.setCorePoolSize(2);
            decoratedExecutor.setMaxPoolSize(2);
            decoratedExecutor.setTaskDecorator(new ContextAwareTaskDecorator());
            decoratedExecutor.initialize();
        }

        @AfterEach
        void tearDown() {
            decoratedExecutor.shutdown();
            ThreadLocalContextUtil.reset();
        }

        @Test
        void execute_propagatesFineractContextToWorkerThreads() throws Exception {
            FineractContext expectedContext = ThreadLocalContextUtil.getContext();

            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            List<OutboxEntry> entries = List.of(entry(1L));
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE)).thenReturn(entries).thenReturn(Collections.emptyList());
            when(outboxRepository.getOutboxStats()).thenReturn(Map.of());

            AtomicReference<FineractContext> capturedContext = new AtomicReference<>();
            doAnswer(inv -> {
                capturedContext.set(ThreadLocalContextUtil.getContext());
                return null;
            }).when(handler).dispatch(any());

            FineractProperties props = new FineractProperties();
            FineractSynapseProperties synapse = new FineractSynapseProperties();
            synapse.setOutboxPageSize(PAGE_SIZE);
            synapse.setOutboxThreadPoolSize(2);
            props.setSynapse(synapse);
            SynapseOutboxTasklet tasklet = new SynapseOutboxTasklet(outboxRepository, List.of(handler), CircuitBreakerRegistry.ofDefaults(),
                    props, decoratedExecutor);

            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get()).isEqualTo(expectedContext);
        }
    }

    @Nested
    class ConcurrentCircuitBreaker {

        @org.junit.jupiter.api.BeforeEach
        void stubStats() {
            org.mockito.Mockito.lenient().when(outboxRepository.getOutboxStats()).thenReturn(Map.of());
        }

        @Test
        void execute_circuitBreakerOpenOnOneHandler_resetsItsPendingButOtherHandlerContinues() throws Exception {
            SynapseTaskHandler handlerA = mock(SynapseTaskHandler.class);
            SynapseTaskHandler handlerB = mock(SynapseTaskHandler.class);
            when(handlerA.taskType()).thenReturn("TYPE_A");
            when(handlerB.taskType()).thenReturn("TYPE_B");

            List<OutboxEntry> entriesA = List.of(entry(1L, "TYPE_A"), entry(2L, "TYPE_A"), entry(3L, "TYPE_A"));
            List<OutboxEntry> entriesB = List.of(entry(10L, "TYPE_B"), entry(11L, "TYPE_B"), entry(12L, "TYPE_B"));

            CircuitBreaker mockCb = mock(CircuitBreaker.class);
            CircuitBreakerRegistry mockRegistry = mock(CircuitBreakerRegistry.class);
            when(mockRegistry.circuitBreaker("synapseOutbox")).thenReturn(mockCb);

            Map<String, String> threadToHandler = new java.util.concurrent.ConcurrentHashMap<>();
            AtomicInteger handlerBCalls = new AtomicInteger(0);
            CountDownLatch handlerBTripped = new CountDownLatch(1);

            doAnswer(inv -> {
                String handlerType = threadToHandler.get(Thread.currentThread().getName());
                if ("TYPE_B".equals(handlerType)) {
                    if (handlerBCalls.incrementAndGet() > 1) {
                        handlerBTripped.countDown();
                        throw mock(CallNotPermittedException.class);
                    }
                } else {
                    assertThat(handlerBTripped.await(5, TimeUnit.SECONDS)).isTrue();
                }
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(mockCb).executeRunnable(any());

            when(outboxRepository.claimPending(eq("TYPE_A"), eq(PAGE_SIZE))).thenAnswer(inv -> {
                threadToHandler.put(Thread.currentThread().getName(), "TYPE_A");
                return entriesA;
            }).thenReturn(Collections.emptyList());

            when(outboxRepository.claimPending(eq("TYPE_B"), eq(PAGE_SIZE))).thenAnswer(inv -> {
                threadToHandler.put(Thread.currentThread().getName(), "TYPE_B");
                return entriesB;
            }).thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handlerA, handlerB), mockRegistry, 2);
            tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

            // Handler A: all 3 entries dispatched successfully
            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).markSent(List.of(2L));
            verify(outboxRepository).markSent(List.of(3L));

            // Handler B: first entry sent, then CB opens → remaining 2 reset to PENDING
            verify(outboxRepository).markSent(List.of(10L));
            verify(outboxRepository).resetToPending(List.of(11L, 12L));
        }
    }
}
