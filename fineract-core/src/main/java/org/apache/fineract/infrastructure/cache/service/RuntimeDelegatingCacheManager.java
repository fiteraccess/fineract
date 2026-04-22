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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.cache.CacheApiConstants;
import org.apache.fineract.infrastructure.cache.CacheEnumerations;
import org.apache.fineract.infrastructure.cache.data.CacheData;
import org.apache.fineract.infrastructure.cache.domain.CacheType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
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
@RequiredArgsConstructor
@Slf4j
public class RuntimeDelegatingCacheManager implements CacheManager, InitializingBean {

    @Qualifier("ehCacheManager")
    private final CacheManager ehCacheManager;

    @Qualifier("defaultCacheManager")
    private final CacheManager defaultCacheManager;
    private CacheManager currentCacheManager;

    @Autowired(required = false)
    @Qualifier("redisCacheManagerWithFallback")
    private CacheManager redisCacheManager;

    @Autowired(required = false)
    @Qualifier("ehCacheMaintenanceManager")
    private CacheManager ehCacheMaintenanceManager;

    @Autowired
    private ConfigurationDomainService configurationDomainService;

    @Override
    public void afterPropertiesSet() throws Exception {
        currentCacheManager = defaultCacheManager;
        restorePersistedCacheMode();
    }

    private void restorePersistedCacheMode() {
        try {
            if (configurationDomainService.isDistributedCacheEnabled() && redisCacheManager != null) {
                log.info("Restoring persisted cache mode: MULTI_NODE");
                switchToCache(CacheType.MULTI_NODE);
            } else if (configurationDomainService.isEhcacheEnabled()) {
                log.info("Restoring persisted cache mode: SINGLE_NODE");
                switchToCache(CacheType.SINGLE_NODE);
            }
        } catch (Exception e) {
            log.warn("Could not restore cache mode from DB, starting with NO_CACHE: {}", e.getMessage());
        }
    }

    @Override
    public Cache getCache(final String name) {
        log.debug("RuntimeDelegatingCacheManager.getCache('{}') using: {}", name, currentCacheManager.getClass().getSimpleName());
        return currentCacheManager.getCache(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return currentCacheManager.getCacheNames();
    }

    public Collection<CacheData> retrieveAll() {

        final boolean noCacheEnabled = currentCacheManager == defaultCacheManager;
        final boolean ehCacheEnabled = currentCacheManager == ehCacheManager;
        final boolean multiNodeEnabled = currentCacheManager == redisCacheManager;

        final EnumOptionData noCacheType = CacheEnumerations.cacheType(CacheType.NO_CACHE);
        final EnumOptionData singleNodeCacheType = CacheEnumerations.cacheType(CacheType.SINGLE_NODE);
        final EnumOptionData multiNodeCacheType = CacheEnumerations.cacheType(CacheType.MULTI_NODE);

        final CacheData noCache = CacheData.builder().cacheType(noCacheType).enabled(noCacheEnabled).build();
        final CacheData singleNodeCache = CacheData.builder().cacheType(singleNodeCacheType).enabled(ehCacheEnabled).build();
        final CacheData multiNodeCache = CacheData.builder().cacheType(multiNodeCacheType).enabled(multiNodeEnabled).build();

        return Arrays.asList(noCache, singleNodeCache, multiNodeCache);
    }

    public boolean isCachingEnabled() {
        return currentCacheManager != defaultCacheManager;
    }

    public Map<String, Object> switchToCache(final CacheType toCacheType) {

        final Map<String, Object> changes = new HashMap<>();

        switch (toCacheType) {
            case INVALID -> {
                log.warn("Invalid cache type used");
            }
            case NO_CACHE -> {
                if (currentCacheManager != defaultCacheManager) {
                    changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
                }
                currentCacheManager = defaultCacheManager;
            }
            case SINGLE_NODE -> {
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
                    throw new UnsupportedOperationException("Multi-node cache requires Redis. Set fineract.cache.redis.enabled=true");
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

        clearEhCacheManager(ehCacheManager);
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
