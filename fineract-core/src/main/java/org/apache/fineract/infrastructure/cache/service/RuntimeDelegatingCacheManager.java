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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

    @Qualifier("ehCacheManager")
    private final CacheManager ehCacheManager;
    @Qualifier("defaultCacheManager")
    private final CacheManager defaultCacheManager;
    @Qualifier("redisCacheManager")
    private final CacheManager redisCacheManager;
    private CacheManager currentCacheManager;

    public RuntimeDelegatingCacheManager(@Qualifier("ehCacheManager") CacheManager ehCacheManager,
            @Qualifier("defaultCacheManager") CacheManager defaultCacheManager,
            @Qualifier("redisCacheManager") @Autowired(required = false) CacheManager redisCacheManager) {
        this.ehCacheManager = ehCacheManager;
        this.defaultCacheManager = defaultCacheManager;
        this.redisCacheManager = redisCacheManager;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        currentCacheManager = defaultCacheManager;
    }

    @Override
    public Cache getCache(final String name) {
        return currentCacheManager.getCache(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return currentCacheManager.getCacheNames();
    }

    public Collection<CacheData> retrieveAll() {

        final boolean noCacheEnabled = currentCacheManager == defaultCacheManager;
        final boolean ehCacheEnabled = currentCacheManager == ehCacheManager;
        final boolean redisCacheEnabled = currentCacheManager == redisCacheManager;

        final EnumOptionData noCacheType = CacheEnumerations.cacheType(CacheType.NO_CACHE);
        final EnumOptionData singleNodeCacheType = CacheEnumerations.cacheType(CacheType.SINGLE_NODE);
        final EnumOptionData multiNodeCacheType = CacheEnumerations.cacheType(CacheType.MULTI_NODE);

        final CacheData noCache = CacheData.builder().cacheType(noCacheType).enabled(noCacheEnabled).build();
        final CacheData singleNodeCache = CacheData.builder().cacheType(singleNodeCacheType).enabled(ehCacheEnabled).build();
        final CacheData multiNodeCache = CacheData.builder().cacheType(multiNodeCacheType).enabled(redisCacheEnabled).build();

        return Arrays.asList(noCache, singleNodeCache, multiNodeCache);
    }

    public Map<String, Object> switchToCache(final boolean cacheEnabled, final CacheType toCacheType) {

        final Map<String, Object> changes = new HashMap<>();

        final boolean noCacheEnabled = !cacheEnabled;

        switch (toCacheType) {
            case INVALID -> {
                log.warn("Invalid cache type used");
            }
            case NO_CACHE -> {
                if (!noCacheEnabled) {
                    changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
                }
                currentCacheManager = defaultCacheManager;
            }
            case SINGLE_NODE -> {
                if (!cacheEnabled || currentCacheManager != ehCacheManager) {
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
                    throw new UnsupportedOperationException(
                            "Redis cache is not configured. Enable fineract.redis.enabled=true");
                }

                if (currentCacheManager != redisCacheManager) {
                    changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
                    clearRedisCache();
                }
                currentCacheManager = redisCacheManager;

                if (currentCacheManager.getCacheNames().isEmpty()) {
                    log.error("No caches configured for activated CacheManager {}", currentCacheManager);
                }
            }
        }

        return changes;
    }

    @SuppressFBWarnings(value = "DCN_NULLPOINTER_EXCEPTION", justification = "TODO: fix this!")
    private void clearEhCache() {
        Iterable<String> cacheNames = ehCacheManager.getCacheNames();
        for (String cacheName : cacheNames) {
            try {
                if (Objects.nonNull(ehCacheManager.getCache(cacheName))) {
                    Objects.requireNonNull(ehCacheManager.getCache(cacheName)).clear();
                }
            } catch (NullPointerException npe) {
                log.warn("NullPointerException occurred", npe);
            }
        }
    }

    @SuppressFBWarnings(value = "DCN_NULLPOINTER_EXCEPTION", justification = "TODO: fix this!")
    private void clearRedisCache() {
        if (redisCacheManager == null) {
            return;
        }

        Collection<String> cacheNames = redisCacheManager.getCacheNames();
        for (String cacheName : cacheNames) {
            try {
                Cache cache = redisCacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            } catch (Exception e) {
                log.warn("Error clearing Redis cache: {}", cacheName, e);
            }
        }
    }
}
