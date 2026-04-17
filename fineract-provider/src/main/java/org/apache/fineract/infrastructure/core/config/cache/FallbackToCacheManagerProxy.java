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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Wraps a Redis cache manager and catches connection errors, degrading gracefully to no-cache (cache miss) instead of
 * propagating exceptions. This ensures Redis unavailability never crashes the application.
 */
@Slf4j
public class FallbackToCacheManagerProxy implements CacheManager {

    private final CacheManager delegate;
    private final ConcurrentHashMap.KeySetView<String, Boolean> knownCacheNames = ConcurrentHashMap.newKeySet();

    public FallbackToCacheManagerProxy(CacheManager delegate) {
        this.delegate = delegate;
    }

    public FallbackToCacheManagerProxy(CacheManager delegate, Collection<String> initialCacheNames) {
        this.delegate = delegate;
        this.knownCacheNames.addAll(initialCacheNames);
    }

    @Override
    public Cache getCache(String name) {
        try {
            Cache cache = delegate.getCache(name);
            if (cache != null) {
                knownCacheNames.add(name);
                return new FallbackCache(cache);
            }
        } catch (Exception e) {
            log.warn("Redis cache unavailable, degrading to no-cache for '{}': {}", name, e.getMessage());
        }
        return null;
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(knownCacheNames);
    }

    /**
     * Wraps a Redis {@link Cache} instance and catches all exceptions on cache operations, returning null (cache miss)
     * on failure instead of propagating.
     */
    static class FallbackCache implements Cache {

        private final Cache primary;

        FallbackCache(Cache primary) {
            this.primary = primary;
        }

        @Override
        public String getName() {
            return primary.getName();
        }

        @Override
        public Object getNativeCache() {
            return primary.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            try {
                return primary.get(key);
            } catch (Exception e) {
                log.warn("Redis get failed for key '{}', skipping: {}", key, e.getMessage());
                return null;
            }
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            try {
                return primary.get(key, type);
            } catch (Exception e) {
                log.warn("Redis get failed for key '{}', skipping: {}", key, e.getMessage());
                return null;
            }
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            try {
                return primary.get(key, valueLoader);
            } catch (Exception e) {
                log.warn("Redis get failed for key '{}', skipping: {}", key, e.getMessage());
                return null;
            }
        }

        @Override
        public void put(Object key, Object value) {
            try {
                primary.put(key, value);
            } catch (Exception e) {
                log.warn("Redis put failed for key '{}', skipping: {}", key, e.getMessage());
            }
        }

        @Override
        public void evict(Object key) {
            try {
                primary.evict(key);
            } catch (Exception e) {
                log.warn("Redis evict failed for key '{}': {}", key, e.getMessage());
            }
        }

        @Override
        public void clear() {
            try {
                primary.clear();
            } catch (Exception e) {
                log.warn("Redis clear failed: {}", e.getMessage());
            }
        }
    }
}
