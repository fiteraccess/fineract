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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@RequiredArgsConstructor
public class RedisCacheInvalidationPublisher {

    private final StringRedisTemplate redisTemplate;
    private final String channelPrefix;
    private final String cacheNodeId;
    private final ObjectMapper objectMapper;

    public void publishEviction(String cacheName, Object key) {
        publishAfterCommit(new CacheInvalidationMessage(cacheName, key != null ? key.toString() : null, false, cacheNodeId));
    }

    public void publishClear(String cacheName) {
        publishAfterCommit(new CacheInvalidationMessage(cacheName, null, true, cacheNodeId));
    }

    private void publishAfterCommit(CacheInvalidationMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    doPublish(message);
                }
            });
        } else {
            doPublish(message);
        }
    }

    private void doPublish(CacheInvalidationMessage message) {
        try {
            String channel = channelPrefix + ":" + resolveTenantId();
            String json = objectMapper.writeValueAsString(message);
            Long recipients = redisTemplate.convertAndSend(channel, json);
            log.debug("Published cache invalidation for '{}' to channel '{}' (recipients={})", message.getCacheName(), channel, recipients);

        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize cache invalidation message: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to publish cache invalidation for '{}': {}", message.getCacheName(), e.getMessage());
        }
    }

    private String resolveTenantId() {
        try {
            FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
            if (tenant != null) {
                return tenant.getTenantIdentifier();
            }
        } catch (Exception e) {
            log.debug("Tenant context not available for pub/sub, using 'system'");
        }
        return "system";
    }
}
