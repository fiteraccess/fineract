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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis cache configuration for distributed caching (MULTI_NODE).
 * This configuration is only active when fineract.redis.enabled=true.
 */
@Configuration
@ConditionalOnProperty(name = "fineract.redis.enabled", havingValue = "true")
@Slf4j
public class RedisCacheConfig {

    @Autowired
    private FineractProperties fineractProperties;

    /**
     * Redis connection factory using Lettuce client.
     */
    @Bean
    @SuppressWarnings("unchecked")
    public RedisConnectionFactory redisConnectionFactory() {
        log.info("Configuring Redis connection to {}:{}", fineractProperties.getRedis().getHost(),
                fineractProperties.getRedis().getPort());

        // Basic Redis configuration
        final RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(fineractProperties.getRedis().getHost());
        redisConfig.setPort(fineractProperties.getRedis().getPort());
        redisConfig.setDatabase(fineractProperties.getRedis().getDatabase());

        if (fineractProperties.getRedis().getPassword() != null && !fineractProperties.getRedis().getPassword().isEmpty()) {
            redisConfig.setPassword(fineractProperties.getRedis().getPassword());
        }

        // Connection pool configuration
        final GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(fineractProperties.getRedis().getMaxActive());
        poolConfig.setMaxIdle(fineractProperties.getRedis().getMaxIdle());
        poolConfig.setMinIdle(fineractProperties.getRedis().getMinIdle());
        poolConfig.setMaxWait(java.time.Duration.ofMillis(fineractProperties.getRedis().getMaxWait()));

        // Lettuce client configuration with pooling
        final LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(fineractProperties.getRedis().getTimeout())).poolConfig(poolConfig).build();

        return new LettuceConnectionFactory(redisConfig, clientConfig);
    }

    /**
     * Redis cache manager with dynamic cache configuration. Reuses cache discovery and TTL configuration from existing
     * CacheConfig logic.
     */
    @Bean(name = "redisCacheManager")
    public RedisCacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        log.info("Creating Redis cache manager with dynamic cache configurations");

        // Default cache configuration - reuses TTL from fineractProperties.cache
        final Duration defaultTtl = fineractProperties.getCache().getDefaultTemplate().getTtl();

        final RedisCacheConfiguration defaultConfig = createRedisCacheConfiguration(defaultTtl);

        // Discover cache names using same reflection logic as EhCache
        final Set<String> cacheNames = discoverCacheNames();

        // Apply custom configurations for specific caches (same as EhCache)
        final Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheNames.forEach(cacheName -> {
            Duration ttl = getCacheTtl(cacheName, defaultTtl);
            cacheConfigurations.put(cacheName, createRedisCacheConfiguration(ttl));
        });

        // Validate custom templates
        final Set<String> incorrectConfigurations = new HashSet<>(fineractProperties.getCache().getCustomTemplates().keySet());
        incorrectConfigurations.removeAll(cacheNames);
        if (!incorrectConfigurations.isEmpty()) {
            log.warn("The following cache configurations are defined but cache does not exists: {}", incorrectConfigurations);
        }

        log.info("Configured {} Redis caches: {}", cacheNames.size(), cacheNames);

        return RedisCacheManager.builder(redisConnectionFactory).cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations).transactionAware().build();
    }

    /**
     * Create Redis cache configuration with TTL and serialization.
     */
    private RedisCacheConfiguration createRedisCacheConfiguration(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl).disableCachingNullValues()
                .computePrefixWith(cacheName -> fineractProperties.getRedis().getKeyPrefix() + cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new JdkSerializationRedisSerializer()));
    }

    /**
     * Discover cache names using reflection (same logic as CacheConfig). This ensures both EhCache and Redis have the
     * same caches configured.
     */
    private Set<String> discoverCacheNames() {
        final Reflections reflections = new Reflections(new ConfigurationBuilder().setUrls(ClasspathHelper.forJavaClassPath())
                .forPackage("org.apache.fineract").addScanners(Scanners.MethodsAnnotated, Scanners.TypesAnnotated));

        // Find all methods annotated with @Cacheable
        final Set<Method> annotatedMethods = reflections.getMethodsAnnotatedWith(Cacheable.class);
        final Set<String> cacheNames = annotatedMethods.stream().map(method -> method.getAnnotation(Cacheable.class))
                .flatMap(annotation -> Stream.concat(Arrays.stream(annotation.value()), Arrays.stream(annotation.cacheNames())))
                .collect(Collectors.toSet());

        // Find all types annotated with @Cacheable
        final Set<Class<?>> annotatedClasses = reflections.getTypesAnnotatedWith(Cacheable.class);
        cacheNames.addAll(annotatedClasses.stream().map(clazz -> clazz.getAnnotation(Cacheable.class))
                .flatMap(annotation -> Stream.concat(Arrays.stream(annotation.value()), Arrays.stream(annotation.cacheNames())))
                .collect(Collectors.toSet()));

        // Find all types annotated with @CacheConfig
        final Set<Class<?>> annotatedCacheConfigClasses = reflections
                .getTypesAnnotatedWith(org.springframework.cache.annotation.CacheConfig.class);
        cacheNames.addAll(annotatedCacheConfigClasses.stream()
                .map(clazz -> clazz.getAnnotation(org.springframework.cache.annotation.CacheConfig.class))
                .flatMap(annotation -> Arrays.stream(annotation.cacheNames())).collect(Collectors.toSet()));

        return cacheNames;
    }

    /**
     * Get TTL for specific cache from custom templates or default. Same logic as generateCustomCacheConfiguration in
     * CacheConfig.
     */
    private Duration getCacheTtl(String cacheName, Duration defaultTtl) {
        if (fineractProperties.getCache().getCustomTemplates().containsKey(cacheName)) {
            Duration customTtl = fineractProperties.getCache().getCustomTemplates().get(cacheName).getTtl();
            return Objects.requireNonNullElse(customTtl, defaultTtl);
        }
        return defaultTtl;
    }
}

