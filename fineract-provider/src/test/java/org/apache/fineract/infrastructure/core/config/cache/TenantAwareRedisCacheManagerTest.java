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

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

@ExtendWith(MockitoExtension.class)
class TenantAwareRedisCacheManagerTest {

    private static final String CACHE_NAME = "offices";

    @Mock
    private RedisCacheWriter cacheWriter;

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void getCache_shouldPrefixWithTenantIdentifier() {
        FineractPlatformTenant tenant = new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);

        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

            TenantAwareRedisCacheManager manager = new TenantAwareRedisCacheManager(cacheWriter,
                    RedisCacheConfiguration.defaultCacheConfig());

            Cache cache = manager.getCache(CACHE_NAME);

            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo("default__" + CACHE_NAME);
        }
    }

    @Test
    void getCache_shouldUseFallbackWhenTenantContextMissing() {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(null);

            TenantAwareRedisCacheManager manager = new TenantAwareRedisCacheManager(cacheWriter,
                    RedisCacheConfiguration.defaultCacheConfig());

            Cache cache = manager.getCache(CACHE_NAME);

            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo("system__" + CACHE_NAME);
        }
    }

    @Test
    void getCache_shouldUseFallbackWhenTenantContextThrows() {
        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            mocked.when(ThreadLocalContextUtil::getTenant).thenThrow(new IllegalStateException("No tenant context"));

            TenantAwareRedisCacheManager manager = new TenantAwareRedisCacheManager(cacheWriter,
                    RedisCacheConfiguration.defaultCacheConfig());

            Cache cache = manager.getCache(CACHE_NAME);

            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo("system__" + CACHE_NAME);
        }
    }

    @Test
    void getCache_shouldIsolateDifferentTenants() {
        FineractPlatformTenant tenant1 = new FineractPlatformTenant(1L, "tenant1", "Tenant One", "UTC", null);
        FineractPlatformTenant tenant2 = new FineractPlatformTenant(2L, "tenant2", "Tenant Two", "UTC", null);

        try (MockedStatic<ThreadLocalContextUtil> mocked = Mockito.mockStatic(ThreadLocalContextUtil.class)) {
            TenantAwareRedisCacheManager manager = new TenantAwareRedisCacheManager(cacheWriter,
                    RedisCacheConfiguration.defaultCacheConfig());

            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant1);
            Cache cache1 = manager.getCache(CACHE_NAME);

            mocked.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant2);
            Cache cache2 = manager.getCache(CACHE_NAME);

            assertThat(cache1.getName()).isEqualTo("tenant1__" + CACHE_NAME);
            assertThat(cache2.getName()).isEqualTo("tenant2__" + CACHE_NAME);
            assertThat(cache1.getName()).isNotEqualTo(cache2.getName());
        }
    }
}
