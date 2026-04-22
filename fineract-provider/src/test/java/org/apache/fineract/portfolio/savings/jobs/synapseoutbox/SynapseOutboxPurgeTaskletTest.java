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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSynapseProperties;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
class SynapseOutboxPurgeTaskletTest {

    @Mock
    private SynapseOutboxRepository outboxRepository;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    private SynapseOutboxPurgeTasklet createTasklet(int retentionDays) {
        FineractProperties props = new FineractProperties();
        FineractSynapseProperties synapse = new FineractSynapseProperties();
        synapse.setOutboxRetentionDays(retentionDays);
        props.setSynapse(synapse);
        return new SynapseOutboxPurgeTasklet(outboxRepository, props);
    }

    @Test
    void execute_callsPurgeWithConfiguredRetentionDays() throws Exception {
        int retentionDays = 45;
        when(outboxRepository.purgeOldSentEntries(retentionDays)).thenReturn(10);

        SynapseOutboxPurgeTasklet tasklet = createTasklet(retentionDays);
        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        verify(outboxRepository).purgeOldSentEntries(retentionDays);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void execute_usesDefaultRetentionDays() throws Exception {
        FineractProperties props = new FineractProperties();
        FineractSynapseProperties synapse = new FineractSynapseProperties();
        props.setSynapse(synapse);
        when(outboxRepository.purgeOldSentEntries(30)).thenReturn(0);

        SynapseOutboxPurgeTasklet tasklet = new SynapseOutboxPurgeTasklet(outboxRepository, props);
        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        verify(outboxRepository).purgeOldSentEntries(30);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }
}
