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
package org.apache.fineract.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.apache.fineract.infrastructure.configuration.api.InternalConfigurationsApiResource;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainServiceJpa;
import org.apache.fineract.infrastructure.configuration.service.GlobalConfigurationWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.cache.CacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

class ConfigurationCachingAnnotationsTest {

    @Test
    void configurationReadMethodsShouldBeCacheable() throws Exception {
        assertCacheable(ConfigurationDomainServiceJpa.class.getMethod("retrievePivotDateConfig"), "|retrievePivotDateConfig");
        assertCacheable(ConfigurationDomainServiceJpa.class.getMethod("retrieveRelaxingDaysConfigForPivotDate"),
                "|retrieveRelaxingDaysConfigForPivotDate");
        assertCacheable(ConfigurationDomainServiceJpa.class.getMethod("isSavingsInterestPostingAtCurrentPeriodEnd"),
                "|isSavingsInterestPostingAtCurrentPeriodEnd");
        assertCacheable(ConfigurationDomainServiceJpa.class.getMethod("retrieveFinancialYearBeginningMonth"),
                "|retrieveFinancialYearBeginningMonth");
    }

    @Test
    void configurationWriteMethodsShouldEvictAllEntries() throws Exception {
        assertCacheEvict(GlobalConfigurationWritePlatformServiceJpaRepositoryImpl.class.getMethod("update", Long.class, JsonCommand.class));
        assertCacheEvict(GlobalConfigurationWritePlatformServiceJpaRepositoryImpl.class.getMethod("addSurveyConfig", String.class));
        assertCacheEvict(InternalConfigurationsApiResource.class.getMethod("updateGlobalConfiguration", String.class, Long.class));
    }

    private void assertCacheable(Method method, String keySuffix) {
        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        assertNotNull(cacheable);
        assertEquals(CacheConfig.CONFIG_BY_NAME_CACHE_NAME, cacheable.value()[0]);
        assertTrue(cacheable.key().contains(keySuffix));
    }

    private void assertCacheEvict(Method method) {
        CacheEvict cacheEvict = method.getAnnotation(CacheEvict.class);
        assertNotNull(cacheEvict);
        assertEquals(CacheConfig.CONFIG_BY_NAME_CACHE_NAME, cacheEvict.value()[0]);
        assertTrue(cacheEvict.allEntries());
    }
}
