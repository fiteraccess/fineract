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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.jsr107.Eh107Configuration;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class CacheConfig {

    public static final String CONFIG_BY_NAME_CACHE_NAME = "configByName";
    public static final String GL_CLOSURES_BY_OFFICE_ID_CACHE_NAME = "glClosuresByOfficeId";
    public static final String PAYMENT_TYPES_BY_ID_CACHE_NAME = "paymentTypesById";
    public static final String SAVINGS_PRODUCT_TO_GL_ACCOUNTS_CACHE_NAME = "savingsProductToGLAccounts";
    @Autowired
    private FineractProperties fineractProperties;

    // Ehcache beans only load when Redis is not the active cache backend. With Redis enabled, the
    // RuntimeDelegatingCacheManager routes all @Cacheable traffic to Redis and the ehcache machinery
    // is unused — so we skip loading it entirely to avoid accidental parallel cache populations.
    @Bean
    @ConditionalOnProperty(name = "fineract.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
    public TransactionBoundCacheManager defaultCacheManager(@Qualifier("ehCacheManager") CacheManager ehCacheManager) {
        SpecifiedCacheSupportingCacheManager cacheManager = new SpecifiedCacheSupportingCacheManager();
        cacheManager.setNoOpCacheManager(new NoOpCacheManager());
        cacheManager.setDelegateCacheManager(ehCacheManager);
        cacheManager.setSupportedCaches(CONFIG_BY_NAME_CACHE_NAME, GL_CLOSURES_BY_OFFICE_ID_CACHE_NAME, PAYMENT_TYPES_BY_ID_CACHE_NAME,
                SAVINGS_PRODUCT_TO_GL_ACCOUNTS_CACHE_NAME);
        return new TransactionBoundCacheManager(cacheManager);
    }

    @Bean(name = "ehCacheNativeManager", destroyMethod = "close")
    @ConditionalOnProperty(name = "fineract.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
    public javax.cache.CacheManager ehCacheNativeManager() {
        return createInternalEhCacheManager();
    }

    @Bean("ehCacheManager")
    @ConditionalOnProperty(name = "fineract.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
    public CacheManager ehCacheManager(@Qualifier("ehCacheNativeManager") javax.cache.CacheManager ehCacheNativeManager) {
        return new TenantAwareEhCacheManager(ehCacheNativeManager, buildCacheConfigurations());
    }

    @Bean("ehCacheMaintenanceManager")
    @ConditionalOnProperty(name = "fineract.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
    public CacheManager ehCacheMaintenanceManager(@Qualifier("ehCacheNativeManager") javax.cache.CacheManager ehCacheNativeManager) {
        return new DynamicJCacheCacheManager(ehCacheNativeManager);
    }

    private javax.cache.CacheManager createInternalEhCacheManager() {
        CachingProvider provider = Caching.getCachingProvider();
        return provider.getCacheManager();
    }

    private Map<String, javax.cache.configuration.Configuration<Object, Object>> buildCacheConfigurations() {
        Duration defaultTimeToLive = fineractProperties.getCache().getDefaultTemplate().getTtl();
        Integer defaultMaxEntries = fineractProperties.getCache().getDefaultTemplate().getMaximumEntries();
        javax.cache.configuration.Configuration<Object, Object> defaultTemplate = generateCacheConfiguration(defaultMaxEntries,
                defaultTimeToLive);
        // Scan all packages (entire classpath)
        Reflections reflections = new Reflections(new ConfigurationBuilder().setUrls(ClasspathHelper.forJavaClassPath())
                .addScanners(Scanners.MethodsAnnotated, Scanners.TypesAnnotated));
        // Find all methods annotated with @Cacheable
        Set<Method> annotatedMethods = reflections.getMethodsAnnotatedWith(Cacheable.class);
        Set<String> cacheNames = annotatedMethods.stream().map(method -> method.getAnnotation(Cacheable.class))
                .flatMap(annotation -> Stream.concat(Arrays.stream(annotation.value()), Arrays.stream(annotation.cacheNames())))
                .collect(Collectors.toSet());
        // Find all types annotated with @Cacheable
        Set<Class<?>> annotatedClasses = reflections.getTypesAnnotatedWith(Cacheable.class);
        cacheNames.addAll(annotatedClasses.stream().map(clazz -> clazz.getAnnotation(Cacheable.class))
                .flatMap(annotation -> Stream.concat(Arrays.stream(annotation.value()), Arrays.stream(annotation.cacheNames())))
                .collect(Collectors.toSet()));
        // Find all types annotated with @CacheConfig
        Set<Class<?>> annotatedCacheConfigClasses = reflections
                .getTypesAnnotatedWith(org.springframework.cache.annotation.CacheConfig.class);
        cacheNames.addAll(annotatedCacheConfigClasses.stream()
                .map(clazz -> clazz.getAnnotation(org.springframework.cache.annotation.CacheConfig.class))
                .flatMap(annotation -> Arrays.stream(annotation.cacheNames())).collect(Collectors.toSet()));

        Map<String, javax.cache.configuration.Configuration<Object, Object>> configurations = new LinkedHashMap<>();
        cacheNames.forEach(cacheName -> configurations.put(cacheName,
                generateCustomCacheConfiguration(cacheName, defaultTemplate, defaultTimeToLive, defaultMaxEntries)));

        Set<String> incorrectConfigurations = new HashSet<>(fineractProperties.getCache().getCustomTemplates().keySet());
        incorrectConfigurations.removeAll(cacheNames);
        if (!incorrectConfigurations.isEmpty()) {
            log.warn("The following cache configurations are defined but cache does not exists: {}", incorrectConfigurations);
        }
        return configurations;
    }

    private javax.cache.configuration.Configuration<Object, Object> generateCustomCacheConfiguration(String cacheIdentifier,
            javax.cache.configuration.Configuration<Object, Object> defaultTemplate, Duration defaultTimeToLive,
            Integer defaultMaxEntries) {
        javax.cache.configuration.Configuration<Object, Object> configurationTemplate = defaultTemplate;
        if (fineractProperties.getCache().getCustomTemplates().containsKey(cacheIdentifier)) {
            Duration timeToLiveExpiration = Objects.requireNonNullElse(
                    fineractProperties.getCache().getCustomTemplates().get(cacheIdentifier).getTtl(), defaultTimeToLive);
            Integer maxEntries = Objects.requireNonNullElse(
                    fineractProperties.getCache().getCustomTemplates().get(cacheIdentifier).getMaximumEntries(), defaultMaxEntries);
            configurationTemplate = generateCacheConfiguration(maxEntries, timeToLiveExpiration);
        }
        return configurationTemplate;
    }

    private static javax.cache.configuration.Configuration<Object, Object> generateCacheConfiguration(Integer defaultMaxEntries,
            Duration defaultTimeToLive) {
        return Eh107Configuration.fromEhcacheCacheConfiguration(CacheConfigurationBuilder
                .newCacheConfigurationBuilder(Object.class, Object.class, ResourcePoolsBuilder.heap(defaultMaxEntries))
                .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(defaultTimeToLive)).build());
    }
}
