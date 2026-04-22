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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;

class RuntimeDelegatingCacheManagerTest {

    @Test
    void afterPropertiesSet_shouldBootstrapToSingleNodeWhenOnlyEhCachePresent() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);

        manager.afterPropertiesSet();

        Cache cache = manager.getCache("testCache");
        assertThat(cache).isNotNull();
        assertThat(cache.getName()).isEqualTo("testCache");
        assertThat(manager.isCachingEnabled()).isTrue();
    }

    @Test
    void afterPropertiesSet_shouldBootstrapToNoCacheWhenEhCacheMissing() throws Exception {
        SimpleCacheManager defaultCm = createCacheManager("configByName");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(null, defaultCm);

        manager.afterPropertiesSet();

        assertThat(manager.getCacheNames()).contains("configByName");
        assertThat(manager.isCachingEnabled()).isFalse();
    }

    @Test
    void afterPropertiesSet_shouldBootstrapToMultiNodeWhenRedisPresent() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");
        SimpleCacheManager redis = createCacheManager("testCache", "l2Cache");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);
        injectRedisManager(manager, redis);

        manager.afterPropertiesSet();

        assertThat(manager.getCacheNames()).contains("testCache", "l2Cache");
        assertThat(manager.isCachingEnabled()).isTrue();
    }

    private SimpleCacheManager createCacheManager(String... cacheNames) {
        SimpleCacheManager cm = new SimpleCacheManager();
        cm.setCaches(java.util.Arrays.stream(cacheNames).map(ConcurrentMapCache::new).collect(java.util.stream.Collectors.toList()));
        cm.afterPropertiesSet();
        return cm;
    }

    private void injectRedisManager(RuntimeDelegatingCacheManager manager, CacheManager redis) throws Exception {
        java.lang.reflect.Field field = RuntimeDelegatingCacheManager.class.getDeclaredField("redisCacheManager");
        field.setAccessible(true);
        field.set(manager, redis);
    }
}
