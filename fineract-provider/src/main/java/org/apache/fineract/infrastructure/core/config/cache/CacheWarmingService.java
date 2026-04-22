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

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.RuntimeDelegatingCacheManager;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.delinquency.service.DelinquencyReadPlatformService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsProductReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheWarmingService implements ApplicationListener<ApplicationReadyEvent> {

    private final TenantDetailsService tenantDetailsService;
    private final BusinessDateReadPlatformService businessDateReadPlatformService;
    private final WorkingDaysRepositoryWrapper workingDaysRepositoryWrapper;
    private final ChargeReadPlatformService chargeReadPlatformService;
    private final LoanProductReadPlatformService loanProductReadPlatformService;
    private final SavingsProductReadPlatformService savingsProductReadPlatformService;
    private final CurrencyReadPlatformService currencyReadPlatformService;
    private final DelinquencyReadPlatformService delinquencyReadPlatformService;
    private final AppUserRepositoryWrapper userRepository;

    @Qualifier("runtimeDelegatingCacheManager")
    private final RuntimeDelegatingCacheManager cacheManager;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!isCachingActive()) {
            log.info("Cache warming skipped — caching is not active (NO_CACHE mode)");
            return;
        }

        Thread cacheWarmerThread = newCacheWarmerThread();
        cacheWarmerThread.setDaemon(true);
        cacheWarmerThread.start();
    }

    Thread newCacheWarmerThread() {
        return new Thread(this::warmAllTenants, "cache-warmer");
    }

    boolean isCachingActive() {
        return cacheManager.isCachingEnabled();
    }

    private void warmAllTenants() {
        try {
            List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();
            log.info("Cache warming started for {} tenant(s)", tenants.size());

            for (FineractPlatformTenant tenant : tenants) {
                warmTenant(tenant);
            }

            log.info("Cache warming completed for all tenants");
        } catch (Exception e) {
            log.warn("Cache warming failed, caches will fill organically: {}", e.getMessage());
        }
    }

    private void warmTenant(FineractPlatformTenant tenant) {
        try {
            ThreadLocalContextUtil.setTenant(tenant);
            // Create a fresh security context to avoid modifying any inherited context from the parent thread
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            setupSecurityContext();
            log.info("Warming caches for tenant '{}'", tenant.getTenantIdentifier());
            int warmed = 0;

            warmed += warmSafely("businessDates", () -> businessDateReadPlatformService.getBusinessDates());
            warmed += warmSafely("workingDays", () -> workingDaysRepositoryWrapper.findOne());
            warmed += warmSafely("charges", () -> chargeReadPlatformService.retrieveAllCharges());
            warmed += warmSafely("loanProducts", () -> loanProductReadPlatformService.retrieveAllLoanProducts());
            warmed += warmSafely("savingsProducts", () -> savingsProductReadPlatformService.retrieveAll());
            warmed += warmSafely("currencies", () -> currencyReadPlatformService.retrieveAllPlatformCurrencies());
            warmed += warmSafely("delinquencyRanges", () -> delinquencyReadPlatformService.retrieveAllDelinquencyRanges());
            warmed += warmSafely("delinquencyBuckets", () -> delinquencyReadPlatformService.retrieveAllDelinquencyBuckets());

            log.info("Warmed {}/8 caches for tenant '{}'", warmed, tenant.getTenantIdentifier());
        } catch (Exception e) {
            log.warn("Cache warming failed for tenant '{}': {}", tenant.getTenantIdentifier(), e.getMessage());
        } finally {
            SecurityContextHolder.clearContext();
            ThreadLocalContextUtil.reset();
        }
    }

    private void setupSecurityContext() {
        try {
            AppUser systemUser = userRepository.fetchSystemUser();
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(systemUser, systemUser.getPassword(),
                    systemUser.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            log.debug("Could not set up security context for cache warming: {}", e.getMessage());
        }
    }

    private int warmSafely(String cacheName, Runnable loader) {
        try {
            loader.run();
            return 1;
        } catch (Exception e) {
            log.debug("Failed to warm cache '{}': {}", cacheName, e.getMessage());
            return 0;
        }
    }
}
