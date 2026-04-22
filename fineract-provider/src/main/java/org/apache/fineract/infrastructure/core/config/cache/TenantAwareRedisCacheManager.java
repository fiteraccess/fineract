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

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * A tenant-aware Redis cache manager that prefixes all cache names with the current tenant identifier. This ensures
 * complete namespace isolation in a shared Redis instance across multiple tenants.
 *
 * Redis keys will have the format: {tenantId}__{cacheName}__{annotationKey}
 */
@Slf4j
public class TenantAwareRedisCacheManager extends RedisCacheManager {

    private static final String SEPARATOR = "__";

    public TenantAwareRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration) {
        super(cacheWriter, defaultCacheConfiguration);
    }

    @Override
    public Cache getCache(String name) {
        String tenantId = resolveTenantId();
        String tenantScopedName = tenantId + SEPARATOR + name;
        return super.getCache(tenantScopedName);
    }

    private String resolveTenantId() {
        try {
            FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
            if (tenant != null) {
                return tenant.getTenantIdentifier();
            }
        } catch (Exception e) {
            log.debug("Tenant context not available for Redis cache, using 'system' prefix");
        }
        return "system";
    }
}
