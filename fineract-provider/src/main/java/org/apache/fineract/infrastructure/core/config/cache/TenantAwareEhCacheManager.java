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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.cache.configuration.Configuration;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.jcache.JCacheCache;

/**
 * A tenant-aware cache manager for EhCache that creates a dedicated physical cache per tenant and logical cache name.
 * This ensures complete namespace isolation in a shared EhCache instance across multiple tenants.
 *
 * Cache keys will have the format: {tenantId}__{cacheName}
 *
 * This provides the same tenant isolation behavior as {@link TenantAwareRedisCacheManager} but for EhCache.
 */
@Slf4j
public class TenantAwareEhCacheManager implements CacheManager {

    private static final String DEFAULT_TENANT = "sys";
    private static final String SEPARATOR = "__";

    private final javax.cache.CacheManager delegate;
    private final Map<String, Configuration<Object, Object>> cacheConfigurations;

    // Cache Spring wrappers by tenant-scoped physical cache name to avoid recreating them for every lookup.
    private final ConcurrentHashMap<String, Cache> cacheWrappers = new ConcurrentHashMap<>();

    public TenantAwareEhCacheManager(javax.cache.CacheManager delegate, Map<String, Configuration<Object, Object>> cacheConfigurations) {
        this.delegate = delegate;
        this.cacheConfigurations = Collections.unmodifiableMap(new LinkedHashMap<>(cacheConfigurations));
    }

    @Override
    public Cache getCache(String name) {
        String tenantId = resolveTenantId();
        String tenantScopedName = tenantId + SEPARATOR + name;

        Cache cachedWrapper = cacheWrappers.get(tenantScopedName);
        if (cachedWrapper != null) {
            return cachedWrapper;
        }

        javax.cache.Cache<Object, Object> nativeCache = getOrCreateNativeCache(name, tenantScopedName);
        if (nativeCache == null) {
            return null;
        }

        Cache wrapper = new JCacheCache(nativeCache, true);
        Cache existing = cacheWrappers.putIfAbsent(tenantScopedName, wrapper);
        return existing != null ? existing : wrapper;
    }

    @Override
    public Collection<String> getCacheNames() {
        return cacheConfigurations.keySet();
    }

    private String resolveTenantId() {
        try {
            FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
            if (tenant != null && tenant.getTenantIdentifier() != null) {
                return tenant.getTenantIdentifier();
            }
        } catch (Exception e) {
            log.debug("Tenant context not available for EhCache, using '{}' prefix", DEFAULT_TENANT);
        }
        return DEFAULT_TENANT;
    }

    private javax.cache.Cache<Object, Object> getOrCreateNativeCache(String logicalName, String tenantScopedName) {
        javax.cache.Cache<Object, Object> nativeCache = delegate.getCache(tenantScopedName);
        if (nativeCache != null) {
            return nativeCache;
        }

        Configuration<Object, Object> cacheConfiguration = cacheConfigurations.get(logicalName);
        if (cacheConfiguration == null) {
            return null;
        }

        try {
            delegate.createCache(tenantScopedName, cacheConfiguration);
        } catch (RuntimeException ex) {
            log.debug("EhCache '{}' was created concurrently, reloading existing cache", tenantScopedName, ex);
        }
        return delegate.getCache(tenantScopedName);
    }
}
