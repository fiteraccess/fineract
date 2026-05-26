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
import org.springframework.cache.support.NoOpCache;

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

    // Returning null here would make Spring's AbstractCacheResolver throw "Cannot find cache named 'X'"
    // and fail the request. Instead we return a NoOpCache so the @Cacheable aspect sees a miss and
    // proceeds to invoke the underlying method (e.g. the DB query).
    @Override
    public Cache getCache(String name) {
        try {
            Cache cache = delegate.getCache(name);
            if (cache != null) {
                knownCacheNames.add(name);
                log.debug("Redis cache resolved: name='{}', delegate={}", name, delegate.getClass().getSimpleName());
                return new FallbackCache(cache);
            }
            log.debug("Redis cache '{}' not resolved by delegate {}, degrading to no-cache", name, delegate.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Redis cache unavailable, degrading to no-cache for '{}'", name, e);
        }
        return new NoOpCache(name);
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
                ValueWrapper result = primary.get(key);
                log.debug("Redis get cache='{}' key='{}' hit={}", primary.getName(), key, result != null);
                return result;
            } catch (Exception e) {
                log.warn("Redis get failed for cache='{}' key='{}', skipping", primary.getName(), key, e);
                return null;
            }
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            try {
                T result = primary.get(key, type);
                log.debug("Redis get cache='{}' key='{}' type={} hit={}", primary.getName(), key, type.getSimpleName(), result != null);
                return result;
            } catch (Exception e) {
                log.warn("Redis get failed for cache='{}' key='{}' type={}, skipping", primary.getName(), key, type.getSimpleName(), e);
                return null;
            }
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            try {
                return primary.get(key, valueLoader);
            } catch (Exception e) {
                log.warn("Redis get(valueLoader) failed for cache='{}' key='{}', skipping", primary.getName(), key, e);
                return null;
            }
        }

        @Override
        public void put(Object key, Object value) {
            try {
                primary.put(key, value);
                log.debug("Redis put cache='{}' key='{}' valueType={}", primary.getName(), key,
                        value != null ? value.getClass().getName() : "null");
            } catch (Exception e) {
                log.warn("Redis put failed for cache='{}' key='{}' valueType={}, skipping", primary.getName(), key,
                        value != null ? value.getClass().getName() : "null", e);
            }
        }

        @Override
        public void evict(Object key) {
            try {
                primary.evict(key);
                log.debug("Redis evict cache='{}' key='{}'", primary.getName(), key);
            } catch (Exception e) {
                log.warn("Redis evict failed for cache='{}' key='{}'", primary.getName(), key, e);
            }
        }

        @Override
        public void clear() {
            try {
                primary.clear();
                log.debug("Redis clear cache='{}'", primary.getName());
            } catch (Exception e) {
                log.warn("Redis clear failed for cache='{}'", primary.getName(), e);
            }
        }
    }
}
