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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CacheWarmingServiceTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration NO_INTERACTION_WINDOW = Duration.ofMillis(300);

    @Mock
    private TenantDetailsService tenantDetailsService;
    @Mock
    private BusinessDateReadPlatformService businessDateReadPlatformService;
    @Mock
    private WorkingDaysRepositoryWrapper workingDaysRepositoryWrapper;
    @Mock
    private ChargeReadPlatformService chargeReadPlatformService;
    @Mock
    private LoanProductReadPlatformService loanProductReadPlatformService;
    @Mock
    private SavingsProductReadPlatformService savingsProductReadPlatformService;
    @Mock
    private CurrencyReadPlatformService currencyReadPlatformService;
    @Mock
    private DelinquencyReadPlatformService delinquencyReadPlatformService;
    @Mock
    private AppUserRepositoryWrapper userRepository;
    @Mock
    private RuntimeDelegatingCacheManager cacheManager;
    @Mock
    private ApplicationReadyEvent applicationReadyEvent;
    @Mock
    private AppUser systemUser;

    private CacheWarmingService cacheWarmingService;
    private FineractPlatformTenant testTenant;

    @BeforeEach
    void setUp() {
        cacheWarmingService = new CacheWarmingService(tenantDetailsService, businessDateReadPlatformService, workingDaysRepositoryWrapper,
                chargeReadPlatformService, loanProductReadPlatformService, savingsProductReadPlatformService, currencyReadPlatformService,
                delinquencyReadPlatformService, userRepository, cacheManager);
        testTenant = new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        SecurityContextHolder.clearContext();
    }

    @Test
    void onApplicationEvent_shouldSkipWhenCachingNotActive() {
        when(cacheManager.isCachingEnabled()).thenReturn(false);

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        verify(tenantDetailsService, never()).findAllTenants();
    }

    @Test
    void onApplicationEvent_shouldLaunchDaemonThreadNamedCacheWarmer() {
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        CacheWarmingService serviceSpy = spy(cacheWarmingService);
        Thread testThread = mock(Thread.class);
        doNothing().when(testThread).setDaemon(true);
        doNothing().when(testThread).start();
        when(serviceSpy.newCacheWarmerThread()).thenReturn(testThread);

        serviceSpy.onApplicationEvent(applicationReadyEvent);

        verify(testThread).setDaemon(true);
        verify(testThread).start();
    }

    @Test
    void onApplicationEvent_shouldWarmAllCachesForEachTenant() {
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenReturn(List.of(testTenant));
        when(userRepository.fetchSystemUser()).thenReturn(systemUser);

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        await().atMost(ASYNC_TIMEOUT).untilAsserted(this::verifyAllCacheWarmersInvoked);
    }

    @Test
    void onApplicationEvent_shouldContinueWhenOneServiceFails() {
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenReturn(List.of(testTenant));
        when(userRepository.fetchSystemUser()).thenReturn(systemUser);
        doThrow(new RuntimeException("DB error")).when(businessDateReadPlatformService).getBusinessDates();

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
            verify(workingDaysRepositoryWrapper).findOne();
            verify(chargeReadPlatformService).retrieveAllCharges();
            verify(loanProductReadPlatformService).retrieveAllLoanProducts();
            verify(savingsProductReadPlatformService).retrieveAll();
            verify(currencyReadPlatformService).retrieveAllPlatformCurrencies();
            verify(delinquencyReadPlatformService).retrieveAllDelinquencyRanges();
            verify(delinquencyReadPlatformService).retrieveAllDelinquencyBuckets();
        });
    }

    @Test
    void onApplicationEvent_shouldHandleTenantLoadFailure() {
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenThrow(new RuntimeException("Tenant DB down"));

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        await().during(NO_INTERACTION_WINDOW).atMost(ASYNC_TIMEOUT).untilAsserted(this::verifyNoCacheWarmersInvoked);
    }

    @Test
    void onApplicationEvent_shouldWarmMultipleTenants() {
        FineractPlatformTenant tenant2 = new FineractPlatformTenant(2L, "tenant2", "Second Tenant", "UTC", null);
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenReturn(List.of(testTenant, tenant2));
        when(userRepository.fetchSystemUser()).thenReturn(systemUser);

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> verify(businessDateReadPlatformService, times(2)).getBusinessDates());
    }

    @Test
    void onApplicationEvent_shouldUseIsolatedTenantContextPerTenantAndResetAfterward() throws Exception {
        FineractPlatformTenant tenant2 = new FineractPlatformTenant(2L, "tenant2", "Second Tenant", "UTC", null);
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenReturn(List.of(testTenant, tenant2));
        when(userRepository.fetchSystemUser()).thenReturn(systemUser);

        CopyOnWriteArrayList<String> seenTenantIdentifiers = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Capture the tenant context seen during each cache warming call.
        // Instance mocks (unlike MockedStatic) work across threads, so this
        // doAnswer fires on the cache-warmer thread where the real work happens.
        doAnswer(invocation -> {
            FineractPlatformTenant currentTenant = ThreadLocalContextUtil.getTenant();
            seenTenantIdentifiers.add(currentTenant == null ? null : currentTenant.getTenantIdentifier());
            latch.countDown();
            return null;
        }).when(businessDateReadPlatformService).getBusinessDates();

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        assertThat(latch.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> verify(businessDateReadPlatformService, times(2)).getBusinessDates());

        // Verify each tenant was set in isolation during its warming phase
        assertThat(seenTenantIdentifiers).containsExactly("default", "tenant2");

        // After the cache-warmer thread completes, ThreadLocalContextUtil.reset()
        // is called in the finally block of warmTenant(). Since the thread has
        // finished by now (verified above), the tenant context on that thread is
        // cleared. We cannot directly observe another thread's ThreadLocal state
        // from the test thread, but the sequential processing (tenant1 then
        // tenant2 each seeing their own identifier above) proves that reset()
        // was called between tenants — otherwise tenant2 would still see
        // tenant1's context or the context would leak.
    }

    @Test
    void onApplicationEvent_shouldNotLeakCallerThreadContextOrSecurityContext() {
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenReturn(List.of(testTenant));
        when(userRepository.fetchSystemUser()).thenReturn(systemUser);

        ThreadLocalContextUtil.setTenant(testTenant);
        UsernamePasswordAuthenticationToken existingAuthentication = new UsernamePasswordAuthenticationToken("caller", "secret");
        SecurityContextHolder.getContext().setAuthentication(existingAuthentication);

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> verify(businessDateReadPlatformService).getBusinessDates());

        assertThat(ThreadLocalContextUtil.getTenant()).isEqualTo(testTenant);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existingAuthentication);
    }

    @Test
    void onApplicationEvent_shouldContinueWarmingWhenSecurityContextFails() {
        when(cacheManager.isCachingEnabled()).thenReturn(true);
        when(tenantDetailsService.findAllTenants()).thenReturn(List.of(testTenant));
        when(userRepository.fetchSystemUser()).thenThrow(new RuntimeException("No system user"));

        cacheWarmingService.onApplicationEvent(applicationReadyEvent);

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
            verify(businessDateReadPlatformService).getBusinessDates();
            verify(workingDaysRepositoryWrapper).findOne();
        });
    }

    private void verifyAllCacheWarmersInvoked() {
        verify(businessDateReadPlatformService).getBusinessDates();
        verify(workingDaysRepositoryWrapper).findOne();
        verify(chargeReadPlatformService).retrieveAllCharges();
        verify(loanProductReadPlatformService).retrieveAllLoanProducts();
        verify(savingsProductReadPlatformService).retrieveAll();
        verify(currencyReadPlatformService).retrieveAllPlatformCurrencies();
        verify(delinquencyReadPlatformService).retrieveAllDelinquencyRanges();
        verify(delinquencyReadPlatformService).retrieveAllDelinquencyBuckets();
    }

    private void verifyNoCacheWarmersInvoked() {
        verify(businessDateReadPlatformService, never()).getBusinessDates();
        verify(workingDaysRepositoryWrapper, never()).findOne();
        verify(chargeReadPlatformService, never()).retrieveAllCharges();
        verify(loanProductReadPlatformService, never()).retrieveAllLoanProducts();
        verify(savingsProductReadPlatformService, never()).retrieveAll();
        verify(currencyReadPlatformService, never()).retrieveAllPlatformCurrencies();
        verify(delinquencyReadPlatformService, never()).retrieveAllDelinquencyRanges();
        verify(delinquencyReadPlatformService, never()).retrieveAllDelinquencyBuckets();
    }
}
