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
package org.apache.fineract.infrastructure.cache.service;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.cache.CacheApiConstants;
import org.apache.fineract.infrastructure.cache.CacheEnumerations;
import org.apache.fineract.infrastructure.cache.data.CacheData;
import org.apache.fineract.infrastructure.cache.domain.CacheType;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.stereotype.Component;

/**
 * At present this implementation of {@link CacheManager} just delegates to the real {@link CacheManager} to use.
 *
 * By default it is {@link NoOpCacheManager} but we can change that by checking some persisted configuration in the
 * database on startup and allow user to switch implementation through UI/API
 */
@Component(value = "runtimeDelegatingCacheManager")
@Slf4j
public class RuntimeDelegatingCacheManager implements CacheManager, InitializingBean {

    @Autowired(required = false)
    @Qualifier("ehCacheManager")
    private CacheManager ehCacheManager;

    @Autowired(required = false)
    @Qualifier("defaultCacheManager")
    private CacheManager defaultCacheManager;

    private CacheManager currentCacheManager;
    private final NoOpCacheManager noOpCacheManager = new NoOpCacheManager();

    @Autowired(required = false)
    @Qualifier("redisCacheManagerWithFallback")
    private CacheManager redisCacheManager;

    @Autowired(required = false)
    @Qualifier("ehCacheMaintenanceManager")
    private CacheManager ehCacheMaintenanceManager;

    public RuntimeDelegatingCacheManager() {}

    // Test-only constructor preserving the previous two-arg signature used by existing unit tests.
    RuntimeDelegatingCacheManager(CacheManager ehCacheManager, CacheManager defaultCacheManager) {
        this.ehCacheManager = ehCacheManager;
        this.defaultCacheManager = defaultCacheManager;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        currentCacheManager = resolveDefaultManager();
        bootstrapCacheMode();
    }

    private CacheManager resolveDefaultManager() {
        return defaultCacheManager != null ? defaultCacheManager : noOpCacheManager;
    }

    /**
     * Choose an initial cache mode based on which cache managers Spring has wired in.
     *
     * <p>
     * The per-tenant cache setting in {@code c_cache} is intentionally <em>not</em> consulted here: {@code c_cache} is
     * a tenant-scoped table, but this method runs at bean initialization with no tenant context, so hitting the DB from
     * here queries an arbitrary DataSource (typically the wrong one) and fails with "relation c_cache does not exist".
     * </p>
     *
     * <p>
     * The per-tenant DB setting is still honored — {@code TenantAwareTenantIdentifierFilter} and
     * {@code TenantAwareBasicAuthenticationFilter} invoke {@code switchToCache(...)} on each authenticated request
     * under the correct tenant context. This bootstrap only provides a sensible default so that cache-dependent
     * components (e.g. warming jobs, scheduled tasks) have something usable before the first request arrives.
     * </p>
     */
    private void bootstrapCacheMode() {
        if (redisCacheManager != null) {
            log.info("Bootstrapping cache mode: MULTI_NODE (Redis enabled)");
            switchToCache(CacheType.MULTI_NODE);
        } else if (ehCacheManager != null) {
            log.info("Bootstrapping cache mode: SINGLE_NODE (ehcache enabled, Redis disabled)");
            switchToCache(CacheType.SINGLE_NODE);
        } else {
            log.info("Bootstrapping cache mode: NO_CACHE (no cache managers configured)");
        }
    }

    @Override
    public Cache getCache(final String name) {
        Cache cache = currentCacheManager.getCache(name);
        log.debug("RuntimeDelegatingCacheManager.getCache('{}') delegate={} resolved={}", name,
                currentCacheManager.getClass().getSimpleName(), cache != null);
        return cache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return currentCacheManager.getCacheNames();
    }

    public Collection<CacheData> retrieveAll() {

        final boolean noCacheEnabled = currentCacheManager == defaultCacheManager || currentCacheManager == noOpCacheManager;
        final boolean ehCacheEnabled = ehCacheManager != null && currentCacheManager == ehCacheManager;
        final boolean multiNodeEnabled = redisCacheManager != null && currentCacheManager == redisCacheManager;

        final EnumOptionData noCacheType = CacheEnumerations.cacheType(CacheType.NO_CACHE);
        final EnumOptionData singleNodeCacheType = CacheEnumerations.cacheType(CacheType.SINGLE_NODE);
        final EnumOptionData multiNodeCacheType = CacheEnumerations.cacheType(CacheType.MULTI_NODE);

        final CacheData noCache = CacheData.builder().cacheType(noCacheType).enabled(noCacheEnabled).build();
        final CacheData singleNodeCache = CacheData.builder().cacheType(singleNodeCacheType).enabled(ehCacheEnabled).build();
        final CacheData multiNodeCache = CacheData.builder().cacheType(multiNodeCacheType).enabled(multiNodeEnabled).build();

        return Arrays.asList(noCache, singleNodeCache, multiNodeCache);
    }

    public boolean isCachingEnabled() {
        return currentCacheManager != defaultCacheManager && currentCacheManager != noOpCacheManager;
    }

    public Map<String, Object> switchToCache(final CacheType toCacheType) {

        final Map<String, Object> changes = new HashMap<>();

        switch (toCacheType) {
            case INVALID -> {
                log.warn("Invalid cache type used");
            }
            case NO_CACHE -> {
                CacheManager target = resolveDefaultManager();
                if (currentCacheManager != target) {
                    changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
                }
                currentCacheManager = target;
            }
            case SINGLE_NODE -> {
                if (ehCacheManager == null) {
                    throw new GeneralPlatformDomainRuleException("error.msg.cache.single.node.not.available",
                            "Cannot switch to SINGLE_NODE cache: ehcache is disabled because the server is running in Redis-only mode "
                                    + "(fineract.cache.redis.enabled=true). Only NO_CACHE and MULTI_NODE are available.");
                }
                if (currentCacheManager != ehCacheManager) {
                    changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
                    clearEhCache();
                }
                currentCacheManager = ehCacheManager;

                if (currentCacheManager.getCacheNames().isEmpty()) {
                    log.error("No caches configured for activated CacheManager {}", currentCacheManager);
                }
            }
            case MULTI_NODE -> {
                if (redisCacheManager == null) {
                    throw new GeneralPlatformDomainRuleException("error.msg.cache.multi.node.not.available",
                            "Cannot switch to MULTI_NODE cache: Redis is disabled "
                                    + "(fineract.cache.redis.enabled=false). Only NO_CACHE and SINGLE_NODE are available.");
                }
                if (currentCacheManager != redisCacheManager) {
                    changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
                    clearEhCache();
                }
                currentCacheManager = redisCacheManager;
            }
        }

        return changes;
    }

    private void clearEhCache() {
        if (ehCacheMaintenanceManager != null) {
            clearEhCacheManager(ehCacheMaintenanceManager);
            return;
        }

        if (ehCacheManager != null) {
            clearEhCacheManager(ehCacheManager);
        }
    }

    private void clearEhCacheManager(CacheManager cacheManager) {
        Iterable<String> cacheNames = cacheManager.getCacheNames();
        for (String cacheName : cacheNames) {
            try {
                Cache cache = cacheManager.getCache(cacheName);
                if (Objects.nonNull(cache)) {
                    cache.clear();
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to clear EhCache '{}'", cacheName, ex);
            }
        }
    }
}
