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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSynapseProperties;
import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTaskHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.repeat.RepeatStatus;

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
        FineractProperties props = new FineractProperties();
        FineractSynapseProperties synapse = new FineractSynapseProperties();
        synapse.setOutboxPageSize(PAGE_SIZE);
        props.setSynapse(synapse);
        return new SynapseOutboxTasklet(outboxRepository, handlers, registry, props);
    }

    private static OutboxEntry entry(long id) {
        return OutboxEntry.builder().id(id).taskType("INTEREST_POSTING").build();
    }

    @Nested
    class Execute {

        @Test
        void execute_noHandlers_returnsFinished() throws Exception {
            SynapseOutboxTasklet tasklet = createTasklet(Collections.emptyList());

            RepeatStatus status = tasklet.execute(null, null);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            verifyNoInteractions(outboxRepository);
        }

        @Test
        void execute_noPendingRows_returnsFinished() throws Exception {
            when(handler.taskType()).thenReturn("INTEREST_POSTING");
            when(outboxRepository.claimPending("INTEREST_POSTING", PAGE_SIZE)).thenReturn(Collections.emptyList());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler));
            RepeatStatus status = tasklet.execute(null, null);

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
            tasklet.execute(null, null);

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
            tasklet.execute(null, null);

            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).markFailed(eq(2L), eq("posting failed"));
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
            doAnswer(inv -> { inv.<Runnable>getArgument(0).run(); return null; })
                    .doThrow(mock(CallNotPermittedException.class))
                    .when(mockCb).executeRunnable(any());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler), mockRegistry);
            tasklet.execute(null, null);

            verify(outboxRepository).markSent(List.of(1L));
            verify(outboxRepository).resetToPending(List.of(2L, 3L));
            verify(outboxRepository, never()).markFailed(any(), anyString());
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
            tasklet.execute(null, null);

            verify(outboxRepository).markFailed(eq(1L), eq("java.lang.RuntimeException: something broke"));
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
            tasklet.execute(null, null);

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

            // First entry triggers circuit breaker open immediately
            doThrow(mock(CallNotPermittedException.class)).when(mockCb).executeRunnable(any());

            SynapseOutboxTasklet tasklet = createTasklet(List.of(handler), mockRegistry);
            tasklet.execute(null, null);

            // claimPending called only once — no second page fetch after CB opens
            verify(outboxRepository).claimPending("INTEREST_POSTING", PAGE_SIZE);
            verify(outboxRepository).resetToPending(List.of(1L, 2L));
            verify(outboxRepository, never()).markSent(any());
        }
    }
}
