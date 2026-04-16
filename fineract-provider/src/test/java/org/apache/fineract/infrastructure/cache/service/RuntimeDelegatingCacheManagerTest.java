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
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;

@ExtendWith(MockitoExtension.class)
class RuntimeDelegatingCacheManagerTest {

    @Mock
    private ConfigurationDomainService configurationDomainService;

    @Test
    void afterPropertiesSet_shouldRestoreSingleNodeModeFromDb() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);
        injectConfigService(manager);

        when(configurationDomainService.isDistributedCacheEnabled()).thenReturn(false);
        when(configurationDomainService.isEhcacheEnabled()).thenReturn(true);

        manager.afterPropertiesSet();

        Cache cache = manager.getCache("testCache");
        assertThat(cache).isNotNull();
        assertThat(cache.getName()).isEqualTo("testCache");
    }

    @Test
    void afterPropertiesSet_shouldDefaultToNoCacheWhenDbFails() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);
        injectConfigService(manager);

        when(configurationDomainService.isDistributedCacheEnabled()).thenThrow(new RuntimeException("DB down"));

        manager.afterPropertiesSet();

        // Should still be in NO_CACHE mode (default) — app doesn't crash
        assertThat(manager.getCacheNames()).contains("configByName");
    }

    @Test
    void afterPropertiesSet_shouldRestoreMultiNodeModeWhenAvailable() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");
        SimpleCacheManager twoLevel = createCacheManager("testCache", "l2Cache");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);
        injectConfigService(manager);
        injectTwoLevelManager(manager, twoLevel);

        when(configurationDomainService.isDistributedCacheEnabled()).thenReturn(true);

        manager.afterPropertiesSet();

        assertThat(manager.getCacheNames()).contains("testCache", "l2Cache");
    }

    private SimpleCacheManager createCacheManager(String... cacheNames) {
        SimpleCacheManager cm = new SimpleCacheManager();
        cm.setCaches(java.util.Arrays.stream(cacheNames).map(ConcurrentMapCache::new).collect(java.util.stream.Collectors.toList()));
        cm.afterPropertiesSet();
        return cm;
    }

    private void injectConfigService(RuntimeDelegatingCacheManager manager) throws Exception {
        java.lang.reflect.Field field = RuntimeDelegatingCacheManager.class.getDeclaredField("configurationDomainService");
        field.setAccessible(true);
        field.set(manager, configurationDomainService);
    }

    @Test
    void isCachingEnabled_shouldReturnFalseInNoCacheMode() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);
        injectConfigService(manager);
        when(configurationDomainService.isDistributedCacheEnabled()).thenReturn(false);
        when(configurationDomainService.isEhcacheEnabled()).thenReturn(false);

        manager.afterPropertiesSet();

        assertThat(manager.isCachingEnabled()).isFalse();
    }

    @Test
    void isCachingEnabled_shouldReturnTrueInSingleNodeMode() throws Exception {
        SimpleCacheManager ehCache = createCacheManager("testCache");
        SimpleCacheManager defaultCm = createCacheManager("configByName");

        RuntimeDelegatingCacheManager manager = new RuntimeDelegatingCacheManager(ehCache, defaultCm);
        injectConfigService(manager);
        when(configurationDomainService.isDistributedCacheEnabled()).thenReturn(false);
        when(configurationDomainService.isEhcacheEnabled()).thenReturn(true);

        manager.afterPropertiesSet();

        assertThat(manager.isCachingEnabled()).isTrue();
    }

    private void injectTwoLevelManager(RuntimeDelegatingCacheManager manager, CacheManager twoLevel) throws Exception {
        java.lang.reflect.Field field = RuntimeDelegatingCacheManager.class.getDeclaredField("redisCacheManager");
        field.setAccessible(true);
        field.set(manager, twoLevel);
    }
}
