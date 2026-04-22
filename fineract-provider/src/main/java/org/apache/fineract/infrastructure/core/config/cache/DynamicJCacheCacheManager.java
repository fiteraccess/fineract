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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.jcache.JCacheCache;

/**
 * Spring {@link CacheManager} view over a JCache manager that always reflects the current native cache set.
 *
 * This is used for maintenance operations such as clearing all physical EhCache regions after mode switches, including
 * tenant-scoped caches created lazily at runtime.
 */
public class DynamicJCacheCacheManager implements CacheManager {

    private final javax.cache.CacheManager delegate;
    private final ConcurrentHashMap<String, Cache> cacheWrappers = new ConcurrentHashMap<>();

    public DynamicJCacheCacheManager(javax.cache.CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        javax.cache.Cache<Object, Object> nativeCache = delegate.getCache(name);
        if (nativeCache == null) {
            return null;
        }

        Cache cachedWrapper = cacheWrappers.get(name);
        if (cachedWrapper != null) {
            return cachedWrapper;
        }

        Cache wrapper = new JCacheCache(nativeCache, true);
        Cache existing = cacheWrappers.putIfAbsent(name, wrapper);
        return existing != null ? existing : wrapper;
    }

    @Override
    public Collection<String> getCacheNames() {
        Set<String> cacheNames = new LinkedHashSet<>();
        for (String cacheName : delegate.getCacheNames()) {
            cacheNames.add(cacheName);
        }
        return Collections.unmodifiableSet(cacheNames);
    }
}
