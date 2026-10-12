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
package org.apache.fineract.cob.eod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The WAITING loop: an outage is retried on the next poll; a refusal, a defect or a step failure ends the wait at once.
 */
class AbstractEodStepTest {

    private EodRunService runService;
    private EodRun run;

    @BeforeEach
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void aSynapseOutageIsRetriedOnTheNextPoll_andRecordedAsLastError() {
        PollingStep step = new PollingStep(1, polls(() -> {
            throw synapseError(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        }, () -> AbstractEodStep.Poll.done(Map.of("ok", true))));

        step.execute(run);

        verify(runService).waiting(any(), argThat(detail -> String.valueOf(detail.get("lastError")).contains("503")));
        verify(runService).completed(any(), argThat(detail -> Boolean.TRUE.equals(detail.get("ok"))));
    }

    @Test
    void aTimeoutNamesTheLastError() {
        PollingStep step = new PollingStep(0, polls(() -> {
            throw synapseError(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));
        }));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> step.execute(run));

        assertEquals(AbstractEodStep.CODE_TIMEOUT, thrown.getCode());
        assertTrue(thrown.getMessage().contains("lastError"));
        assertTrue(thrown.getMessage().contains("502"));
    }

    @Test
    void a403EndsTheWaitAtOnce_sayingThePermissionIsMissing() {
        PollingStep step = new PollingStep(1, polls(() -> {
            throw synapseError(new HttpClientErrorException(HttpStatus.FORBIDDEN, "Forbidden", new byte[0], StandardCharsets.UTF_8));
        }));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> step.execute(run));

        assertEquals(AbstractEodStep.CODE_SYNAPSE_REFUSED, thrown.getCode());
        assertTrue(thrown.getMessage().contains("lacks the route's permission"));
        assertEquals(1, step.polled);
    }

    @Test
    void aConnectionErrorOrA429IsRetried() {
        PollingStep step = new PollingStep(1, polls(() -> {
            throw synapseError(new ResourceAccessException("connection refused"));
        }, () -> {
            throw synapseError(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        }, () -> AbstractEodStep.Poll.done(Map.of("ok", true))));

        step.execute(run);

        assertEquals(3, step.polled);
        verify(runService).completed(any(), argThat(detail -> Boolean.TRUE.equals(detail.get("ok"))));
    }

    /** A defect, such as a response body that did not parse into what the step reads, is never retried for an hour. */
    @Test
    void aDefectEndsTheWaitAtOnce() {
        PollingStep step = new PollingStep(1, polls(() -> {
            throw new NullPointerException("status.getInterestRows() is null");
        }));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> step.execute(run));

        assertEquals(AbstractEodStep.CODE_POLL_ERROR, thrown.getCode());
        assertEquals(1, step.polled);
    }

    @Test
    void aStepFailureEndsTheWaitAtOnce() {
        PollingStep step = new PollingStep(1, polls(() -> {
            throw new EodStepFailedException("EOD-X", "real failure");
        }));

        assertEquals("EOD-X", assertThrows(EodStepFailedException.class, () -> step.execute(run)).getCode());
        assertEquals(1, step.polled);
    }

    private static SynapsePostingException synapseError(RuntimeException cause) {
        return new SynapsePostingException("Synapse replay status read failed with HTTP " + cause.getMessage(), cause);
    }

    @SafeVarargs
    private static Deque<Supplier<AbstractEodStep.Poll>> polls(Supplier<AbstractEodStep.Poll>... answers) {
        return new ArrayDeque<>(List.of(answers));
    }

    private final class PollingStep extends AbstractEodStep {

        private final Deque<Supplier<Poll>> answers;
        private int polled;

        PollingStep(int timeoutMinutes, Deque<Supplier<Poll>> answers) {
            super(AbstractEodStepTest.this.runService, EodStepTestSupport.properties(timeoutMinutes));
            this.answers = answers;
        }

        @Override
        protected Map<String, Object> run(EodRun run, EodStep step) {
            return waitUntil(step, () -> {
                polled++;
                return answers.size() > 1 ? answers.poll().get() : answers.peek().get();
            });
        }

        @Override
        public String getEnumStyledName() {
            return "EOD_TEST";
        }

        @Override
        public String getHumanReadableName() {
            return "test step";
        }
    }
}
