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
package org.apache.fineract.infrastructure.core.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RedisCacheInvalidationPublisherTest {

    private static final String CHANNEL_PREFIX = "fineract:cache:invalidate";
    private static final String NODE_ID = "pod-test-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisCacheInvalidationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisCacheInvalidationPublisher(redisTemplate, CHANNEL_PREFIX, NODE_ID, objectMapper);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishEviction_shouldPublishImmediatelyWhenNoTransaction() throws Exception {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            FineractPlatformTenant tenant = new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

            publisher.publishEviction("loanProducts", "default|loan_products");

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(channelCaptor.capture(), messageCaptor.capture());

            assertThat(channelCaptor.getValue()).isEqualTo(CHANNEL_PREFIX + ":default");

            CacheInvalidationMessage msg = objectMapper.readValue(messageCaptor.getValue(), CacheInvalidationMessage.class);
            assertThat(msg.getCacheName()).isEqualTo("loanProducts");
            assertThat(msg.getKey()).isEqualTo("default|loan_products");
            assertThat(msg.isAllEntries()).isFalse();
            assertThat(msg.getSourceNodeId()).isEqualTo(NODE_ID);
        }
    }

    @Test
    void publishEviction_shouldDeferUntilAfterCommitWhenTransactionActive() {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(null);

            TransactionSynchronizationManager.initSynchronization();

            publisher.publishEviction("loanProducts", "key1");

            // Not published yet — deferred
            verify(redisTemplate, never()).convertAndSend(anyString(), anyString());

            // Simulate transaction commit
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCommit();

            // Now published
            verify(redisTemplate).convertAndSend(anyString(), anyString());
        }
    }

    @Test
    void publishClear_shouldSendMessageWithAllEntriesTrue() throws Exception {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            FineractPlatformTenant tenant = new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

            publisher.publishClear("loanProducts");

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq(CHANNEL_PREFIX + ":default"), messageCaptor.capture());

            CacheInvalidationMessage msg = objectMapper.readValue(messageCaptor.getValue(), CacheInvalidationMessage.class);
            assertThat(msg.isAllEntries()).isTrue();
            assertThat(msg.getKey()).isNull();
        }
    }

    @Test
    void publishEviction_shouldUseSystemPrefixWhenNoTenantContext() throws Exception {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(null);

            publisher.publishEviction("offices", "key1");

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(channelCaptor.capture(), anyString());
            assertThat(channelCaptor.getValue()).isEqualTo(CHANNEL_PREFIX + ":system");
        }
    }

    @Test
    void publishEviction_shouldSwallowRedisException() {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(null);
            doThrow(new RuntimeException("Redis down")).when(redisTemplate).convertAndSend(anyString(), anyString());

            publisher.publishEviction("offices", "key1");
        }
    }

    @Test
    void publishEviction_shouldHandleNullKey() throws Exception {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(null);

            publisher.publishEviction("offices", null);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(anyString(), messageCaptor.capture());

            CacheInvalidationMessage msg = objectMapper.readValue(messageCaptor.getValue(), CacheInvalidationMessage.class);
            assertThat(msg.getKey()).isNull();
            assertThat(msg.isAllEntries()).isFalse();
        }
    }
}
