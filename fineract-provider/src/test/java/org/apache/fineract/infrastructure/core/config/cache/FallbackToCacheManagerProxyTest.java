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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCache;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class FallbackToCacheManagerProxyTest {

    private static final String CACHE_NAME = "offices";
    private static final String KEY = "default|of.1";
    private static final String VALUE = "Office Data";

    @Mock
    private CacheManager delegate;

    @Mock
    private Cache delegateCache;

    private FallbackToCacheManagerProxy proxy;

    @BeforeEach
    void setUp() {
        proxy = new FallbackToCacheManagerProxy(delegate);
    }

    // --- CacheManager-level tests ---

    @Test
    void getCache_shouldReturnFallbackCacheWhenDelegateSucceeds() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);

        Cache result = proxy.getCache(CACHE_NAME);

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(FallbackToCacheManagerProxy.FallbackCache.class);
    }

    @Test
    void getCache_shouldDegradeToNoOpWhenDelegateThrows() {
        when(delegate.getCache(CACHE_NAME)).thenThrow(new RedisConnectionFailureException("Connection refused"));

        Cache result = proxy.getCache(CACHE_NAME);

        assertThat(result).isInstanceOf(NoOpCache.class);
        assertThat(result.getName()).isEqualTo(CACHE_NAME);
    }

    @Test
    void getCache_shouldDegradeToNoOpWhenDelegateReturnsNull() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(null);

        Cache result = proxy.getCache(CACHE_NAME);

        assertThat(result).isInstanceOf(NoOpCache.class);
        assertThat(result.getName()).isEqualTo(CACHE_NAME);
    }

    @Test
    void getCacheNames_shouldReturnEmptyWhenNoCachesAccessed() {
        assertThat(proxy.getCacheNames()).isEmpty();
    }

    // --- FallbackCache-level tests ---

    @Test
    void fallbackCache_get_shouldReturnNullOnException() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);
        when(delegateCache.get(KEY)).thenThrow(new RedisConnectionFailureException("Connection refused"));

        Cache.ValueWrapper result = fallbackCache.get(KEY);

        assertThat(result).isNull();
    }

    @Test
    void fallbackCache_get_shouldDelegateOnSuccess() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);
        Cache.ValueWrapper expected = () -> VALUE;
        when(delegateCache.get(KEY)).thenReturn(expected);

        Cache.ValueWrapper result = fallbackCache.get(KEY);

        assertThat(result.get()).isEqualTo(VALUE);
    }

    @Test
    void fallbackCache_getWithType_shouldReturnNullOnException() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);
        when(delegateCache.get(KEY, String.class)).thenThrow(new RedisConnectionFailureException("Connection refused"));

        String result = fallbackCache.get(KEY, String.class);

        assertThat(result).isNull();
    }

    @Test
    void fallbackCache_put_shouldSwallowException() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);
        doThrow(new RedisConnectionFailureException("Connection refused")).when(delegateCache).put(KEY, VALUE);

        // Should not throw
        fallbackCache.put(KEY, VALUE);
    }

    @Test
    void fallbackCache_put_shouldDelegateOnSuccess() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);

        fallbackCache.put(KEY, VALUE);

        verify(delegateCache).put(KEY, VALUE);
    }

    @Test
    void fallbackCache_evict_shouldSwallowException() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);
        doThrow(new RedisConnectionFailureException("Connection refused")).when(delegateCache).evict(KEY);

        // Should not throw
        fallbackCache.evict(KEY);
    }

    @Test
    void fallbackCache_evict_shouldDelegateOnSuccess() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);

        fallbackCache.evict(KEY);

        verify(delegateCache).evict(KEY);
    }

    @Test
    void fallbackCache_clear_shouldSwallowException() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);
        doThrow(new RedisConnectionFailureException("Connection refused")).when(delegateCache).clear();

        // Should not throw
        fallbackCache.clear();
    }

    @Test
    void fallbackCache_getName_shouldDelegate() {
        when(delegate.getCache(CACHE_NAME)).thenReturn(delegateCache);
        when(delegateCache.getName()).thenReturn(CACHE_NAME);
        Cache fallbackCache = proxy.getCache(CACHE_NAME);

        assertThat(fallbackCache.getName()).isEqualTo(CACHE_NAME);
    }
}
