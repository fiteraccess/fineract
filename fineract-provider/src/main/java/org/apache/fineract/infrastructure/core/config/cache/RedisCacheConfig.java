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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulConnection;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.organisation.office.domain.Office;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.core.GrantedAuthority;

/**
 * Redis L2 cache configuration. Only active when {@code fineract.cache.redis.enabled=true}.
 *
 * Redis auto-configuration is excluded in application.properties; this class provides all Redis beans explicitly.
 */
@Configuration
@ConditionalOnProperty(name = "fineract.cache.redis.enabled", havingValue = "true")
public class RedisCacheConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(FineractProperties fineractProperties) {
        FineractProperties.FineractRedisProperties redis = fineractProperties.getCache().getRedis();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redis.getHost());
        config.setPort(redis.getPort());
        config.setDatabase(redis.getDatabase());
        if (StringUtils.isNotBlank(redis.getPassword())) {
            config.setPassword(RedisPassword.of(redis.getPassword()));
        }

        // Connection pool sized for high-concurrency pod deployments (2000 VU target).
        // Lettuce is non-blocking but pooling prevents contention on a single connection
        // under heavy concurrent cache access from request threads and COB batch jobs.
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMinIdle(redis.getPoolMinIdle());
        poolConfig.setMaxIdle(redis.getPoolMaxIdle());
        poolConfig.setMaxTotal(redis.getPoolMaxActive());

        // Reject commands immediately when disconnected instead of buffering them
        // (default buffers indefinitely, causing threads to hang until reconnect)
        ClientOptions clientOptions = ClientOptions.builder().disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder clientConfigBuilder = LettucePoolingClientConfiguration
                .builder().poolConfig((GenericObjectPoolConfig<StatefulConnection<?, ?>>) poolConfig).clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(redis.getCommandTimeoutSeconds()))
                .shutdownTimeout(Duration.ofSeconds(redis.getShutdownTimeoutSeconds()));

        // Enable SSL/TLS for AWS ElastiCache and other managed Redis services that require encryption
        if (redis.isSsl()) {
            clientConfigBuilder.useSsl();
        }

        return new LettuceConnectionFactory(config, clientConfigBuilder.build());
    }

    @Bean("redisCacheManagerWithFallback")
    public FallbackToCacheManagerProxy redisCacheManagerWithFallback(FineractProperties fineractProperties,
            LettuceConnectionFactory redisConnectionFactory) {
        long ttl = fineractProperties.getCache().getRedis().getDefaultTtlSeconds();

        // Restrict Jackson polymorphic deserialization to trusted packages only.
        // The default GenericJackson2JsonRedisSerializer uses LaissezFaireSubTypeValidator
        // which allows any class — a deserialization gadget risk if Redis is compromised.
        ObjectMapper redisObjectMapper = new ObjectMapper();

        // Register JavaTimeModule to support Java 8 date/time types (LocalDate, LocalDateTime, etc.)
        // Without this, serialization of entities containing date fields will fail.
        redisObjectMapper.registerModule(new JavaTimeModule());
        // Write dates as ISO-8601 strings instead of numeric timestamps for readability
        redisObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Ignore unknown properties during deserialization. This is necessary because:
        // 1. JPA entities may have @Transient fields (like AbstractPersistableCustom.isNew) that get
        // serialized but have no setter for deserialization
        // 2. Entity structure may evolve between cache writes and reads during rolling deployments
        redisObjectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // AppUser implements UserDetails which has getAuthorities() — a computed method with no setter.
        // Jackson cannot deserialize it. Since authorities are recomputed from the roles collection
        // (which IS serialized), we tell Jackson to skip this property entirely for Redis caching.
        redisObjectMapper.addMixIn(org.apache.fineract.useradministration.domain.AppUser.class, AppUserCacheMixin.class);
        redisObjectMapper.addMixIn(org.apache.fineract.organisation.office.domain.Office.class, OfficeCacheMixin.class);

        // Custom type resolver that includes final types like Long and String.
        // The default NON_FINAL skips final classes, causing Long to serialize as plain "42"
        // and deserialize back as Integer — triggering ClassCastException in CGLIB proxies.
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).allowIfSubTypeIsArray()
                .allowIfSubType("org.apache.fineract.").allowIfSubType("java.").build();
        redisObjectMapper.setDefaultTyping(new ObjectMapper.DefaultTypeResolverBuilder(ObjectMapper.DefaultTyping.NON_FINAL, ptv) {

            @Override
            public boolean useForType(JavaType t) {
                // Include all non-primitive types (including final classes like Long, String)
                return !t.isPrimitive();
            }
        }.init(JsonTypeInfo.Id.CLASS, null).inclusion(JsonTypeInfo.As.WRAPPER_ARRAY));

        RedisCacheConfiguration redisCacheConfig = RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofSeconds(ttl))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper)));

        TenantAwareRedisCacheManager redisCacheManager = new TenantAwareRedisCacheManager(
                RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory), redisCacheConfig);

        return new FallbackToCacheManagerProxy(redisCacheManager);
    }

    /**
     * Jackson mixin for AppUser Redis serialization. Ignores the computed {@code authorities} property which has no
     * setter and cannot be deserialized. Authorities are always recomputed from the {@code roles} collection via
     * {@code getAuthorities() → populateGrantedAuthorities()}.
     */
    abstract static class AppUserCacheMixin {

        @JsonIgnore
        abstract Collection<GrantedAuthority> getAuthorities();
    }

    /**
     * Jackson mixin for Office Redis serialization. Breaks the bidirectional {@code parent}/{@code children} cycle that
     * otherwise causes infinite recursion during cache writes.
     */
    abstract static class OfficeCacheMixin {

        @JsonIgnore
        abstract List<Office> getChildren();

        @JsonIgnore
        abstract Office getParent();
    }
}
