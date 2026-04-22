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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.cache.Caching;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

class TenantAwareEhCacheManagerTest {

    private static final String CACHE_NAME = "offices";

    private CachingProvider cachingProvider;
    private javax.cache.CacheManager nativeCacheManager;
    private TenantAwareEhCacheManager manager;

    @BeforeEach
    void setUp() {
        cachingProvider = Caching.getCachingProvider();
        nativeCacheManager = cachingProvider.getCacheManager();

        MutableConfiguration<Object, Object> cacheConfiguration = new MutableConfiguration<>();
        cacheConfiguration.setStoreByValue(false);
        cacheConfiguration.setTypes(Object.class, Object.class);

        manager = new TenantAwareEhCacheManager(nativeCacheManager,
                Map.<String, Configuration<Object, Object>>of(CACHE_NAME, cacheConfiguration));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        if (nativeCacheManager != null) {
            nativeCacheManager.close();
        }
    }

    @Test
    void getCache_shouldCreateTenantScopedPhysicalCacheFromLogicalTemplate() {
        setTenant("default");

        Cache cache = manager.getCache(CACHE_NAME);

        assertThat(cache).isNotNull();
        assertThat(cache.getName()).isEqualTo("default__" + CACHE_NAME);
        assertThat(manager.getCacheNames()).containsExactly(CACHE_NAME);
        assertThat(nativeCacheManager.getCache("default__" + CACHE_NAME)).isNotNull();
        assertThat(nativeCacheManager.getCache(CACHE_NAME)).isNull();
    }

    @Test
    void getCache_shouldUseSystemTenantWhenTenantContextMissing() {
        Cache cache = manager.getCache(CACHE_NAME);

        assertThat(cache).isNotNull();
        assertThat(cache.getName()).isEqualTo("sys__" + CACHE_NAME);
        assertThat(nativeCacheManager.getCache("sys__" + CACHE_NAME)).isNotNull();
    }

    @Test
    void getCache_shouldIsolateDifferentTenants() {
        setTenant("tenant1");
        Cache tenantOneCache = manager.getCache(CACHE_NAME);
        tenantOneCache.put("office", "tenant1-value");

        setTenant("tenant2");
        Cache tenantTwoCache = manager.getCache(CACHE_NAME);
        tenantTwoCache.put("office", "tenant2-value");

        assertThat(tenantTwoCache.get("office", String.class)).isEqualTo("tenant2-value");

        setTenant("tenant1");
        assertThat(manager.getCache(CACHE_NAME).get("office", String.class)).isEqualTo("tenant1-value");
    }

    @Test
    void clear_shouldOnlyAffectCurrentTenantCache() {
        setTenant("tenant1");
        manager.getCache(CACHE_NAME).put("office", "tenant1-value");

        setTenant("tenant2");
        manager.getCache(CACHE_NAME).put("office", "tenant2-value");

        setTenant("tenant1");
        manager.getCache(CACHE_NAME).clear();

        assertThat(manager.getCache(CACHE_NAME).get("office")).isNull();

        setTenant("tenant2");
        assertThat(manager.getCache(CACHE_NAME).get("office", String.class)).isEqualTo("tenant2-value");
    }

    @Test
    void getCache_shouldReturnNullForUnsupportedLogicalCache() {
        setTenant("default");

        Cache cache = manager.getCache("unknown");

        assertThat(cache).isNull();
        assertThat(nativeCacheManager.getCache("default__unknown")).isNull();
    }

    private void setTenant(String tenantIdentifier) {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, tenantIdentifier, tenantIdentifier, "UTC", null));
    }
}
