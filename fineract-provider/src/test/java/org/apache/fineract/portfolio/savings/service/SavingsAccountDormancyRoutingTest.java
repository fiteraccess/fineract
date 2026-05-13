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
package org.apache.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseDormancyPostingOutboxWriter;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import sun.misc.Unsafe;

class SavingsAccountDormancyRoutingTest {

    private static final Long ACCOUNT_ID = 42L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 5, 1);
    private static final Long DAYS_TO_INACTIVE = 90L;
    private static final Long DAYS_TO_DORMANCY = 365L;
    private static final Long DAYS_TO_ESCHEAT = 1825L;

    private SavingsAccountWritePlatformServiceJpaRepositoryImpl service;

    private SavingsAccountAssembler savingAccountAssembler;
    private SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper;
    @SuppressWarnings("rawtypes")
    private ObjectProvider synapseDormancyPostingOutboxWriterProvider;
    private SynapseDormancyPostingOutboxWriter outboxWriter;
    private ConfigurationDomainService configurationDomainService;
    private AppUserRepositoryWrapper appuserRepository;
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    private SavingsAccount account;
    private SavingsProduct savingsProduct;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE, BusinessDateType.COB_DATE, BUSINESS_DATE)));

        savingAccountAssembler = mock(SavingsAccountAssembler.class);
        savingAccountRepositoryWrapper = mock(SavingsAccountRepositoryWrapper.class);
        synapseDormancyPostingOutboxWriterProvider = mock(ObjectProvider.class);
        outboxWriter = mock(SynapseDormancyPostingOutboxWriter.class);
        configurationDomainService = mock(ConfigurationDomainService.class);
        appuserRepository = mock(AppUserRepositoryWrapper.class);
        journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);

        savingsProduct = mock(SavingsProduct.class);
        when(savingsProduct.getDaysToInactive()).thenReturn(DAYS_TO_INACTIVE);
        when(savingsProduct.getDaysToDormancy()).thenReturn(DAYS_TO_DORMANCY);
        when(savingsProduct.getDaysToEscheat()).thenReturn(DAYS_TO_ESCHEAT);

        account = mock(SavingsAccount.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.savingsProduct()).thenReturn(savingsProduct);
        when(account.findExistingTransactionIds()).thenReturn(Collections.emptyList());
        when(account.findExistingReversedTransactionIds()).thenReturn(Collections.emptyList());
        when(account.getTransactions()).thenReturn(Collections.emptyList());
        when(account.getCurrency()).thenReturn(new MonetaryCurrency("NGN", 2, 0));

        when(savingAccountAssembler.assembleFrom(eq(ACCOUNT_ID), eq(false))).thenReturn(account);
        when(savingAccountAssembler.assembleFromLightweight(eq(ACCOUNT_ID))).thenReturn(account);

        service = createServiceWithReflection();
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void setSubStatusInactive_routesToOutbox_whenSynapseEnabled() {
        enableSynapse();

        service.setSubStatusInactive(ACCOUNT_ID);

        verify(outboxWriter).postDormancy(eq(account), eq(SavingsAccountSubStatusEnum.INACTIVE), eq(BUSINESS_DATE),
                eq("Inactive threshold reached: " + DAYS_TO_INACTIVE + " days"));
        verify(savingAccountRepositoryWrapper, never()).saveAndFlush(any(SavingsAccount.class));
        verify(account, never()).setSubStatusInactive(false);
        verify(journalEntryWritePlatformService, never()).createJournalEntriesForSavings(any());
    }

    @Test
    void setSubStatusInactive_runsLegacyPath_whenFlagDisabled() {
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(outboxWriter);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(false);

        service.setSubStatusInactive(ACCOUNT_ID);

        verify(outboxWriter, never()).postDormancy(any(), any(), any(), any());
        verify(account).setSubStatusInactive(false);
        verify(savingAccountRepositoryWrapper).saveAndFlush(account);
        assertJournalEntryPostedFor(ACCOUNT_ID);
    }

    @Test
    void setSubStatusInactive_runsLegacyPath_whenProviderUnavailable() {
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(null);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(true);

        service.setSubStatusInactive(ACCOUNT_ID);

        verify(outboxWriter, never()).postDormancy(any(), any(), any(), any());
        verify(account).setSubStatusInactive(false);
        verify(savingAccountRepositoryWrapper).saveAndFlush(account);
        assertJournalEntryPostedFor(ACCOUNT_ID);
    }

    @Test
    void setSubStatusDormant_routesToOutbox_whenSynapseEnabled() {
        enableSynapse();

        service.setSubStatusDormant(ACCOUNT_ID);

        verify(outboxWriter).postDormancy(eq(account), eq(SavingsAccountSubStatusEnum.DORMANT), eq(BUSINESS_DATE),
                eq("Dormant threshold reached: " + DAYS_TO_DORMANCY + " days"));
        verify(savingAccountRepositoryWrapper, never()).saveAndFlush(any(SavingsAccount.class));
        verify(account, never()).setSubStatusDormant();
    }

    @Test
    void setSubStatusDormant_runsLegacyPath_whenFlagDisabled() {
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(outboxWriter);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(false);

        service.setSubStatusDormant(ACCOUNT_ID);

        verify(outboxWriter, never()).postDormancy(any(), any(), any(), any());
        verify(account).setSubStatusDormant();
        verify(savingAccountRepositoryWrapper).saveAndFlush(account);
        verifyNoInteractions(journalEntryWritePlatformService);
    }

    @Test
    void setSubStatusDormant_runsLegacyPath_whenProviderUnavailable() {
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(null);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(true);

        service.setSubStatusDormant(ACCOUNT_ID);

        verify(outboxWriter, never()).postDormancy(any(), any(), any(), any());
        verify(account).setSubStatusDormant();
        verify(savingAccountRepositoryWrapper).saveAndFlush(account);
        verifyNoInteractions(journalEntryWritePlatformService);
    }

    @Test
    void escheat_routesToOutbox_whenSynapseEnabled() {
        enableSynapse();

        service.escheat(ACCOUNT_ID);

        verify(outboxWriter).postDormancy(eq(account), eq(SavingsAccountSubStatusEnum.ESCHEAT), eq(BUSINESS_DATE),
                eq("Escheat threshold reached: " + DAYS_TO_ESCHEAT + " days"));
        verify(savingAccountRepositoryWrapper, never()).saveAndFlush(any(SavingsAccount.class));
        verify(account, never()).escheat(any());
        verify(journalEntryWritePlatformService, never()).createJournalEntriesForSavings(any());
    }

    @Test
    void escheat_runsLegacyPath_whenFlagDisabled() {
        AppUser systemUser = mock(AppUser.class);
        when(appuserRepository.fetchSystemUser()).thenReturn(systemUser);
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(outboxWriter);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(false);

        service.escheat(ACCOUNT_ID);

        verify(outboxWriter, never()).postDormancy(any(), any(), any(), any());
        verify(account).escheat(systemUser);
        verify(savingAccountRepositoryWrapper).saveAndFlush(account);
        assertJournalEntryPostedFor(ACCOUNT_ID);
    }

    @Test
    void escheat_runsLegacyPath_whenProviderUnavailable() {
        AppUser systemUser = mock(AppUser.class);
        when(appuserRepository.fetchSystemUser()).thenReturn(systemUser);
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(null);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(true);

        service.escheat(ACCOUNT_ID);

        verify(outboxWriter, never()).postDormancy(any(), any(), any(), any());
        verify(account).escheat(systemUser);
        verify(savingAccountRepositoryWrapper).saveAndFlush(account);
        assertJournalEntryPostedFor(ACCOUNT_ID);
    }

    @Test
    void setSubStatusInactive_propagatesAndDoesNotMutate_whenOutboxThrows() {
        enableSynapse();
        doThrow(new SynapsePostingException("posting failed")).when(outboxWriter).postDormancy(any(), any(), any(), any());

        assertThatThrownBy(() -> service.setSubStatusInactive(ACCOUNT_ID)).isInstanceOf(SynapsePostingException.class)
                .hasMessage("posting failed");

        verify(account, never()).setSubStatusInactive(false);
        verify(savingAccountRepositoryWrapper, never()).saveAndFlush(any(SavingsAccount.class));
        verifyNoInteractions(journalEntryWritePlatformService);
    }

    @Test
    void setSubStatusDormant_propagatesAndDoesNotMutate_whenOutboxThrows() {
        enableSynapse();
        doThrow(new SynapsePostingException("posting failed")).when(outboxWriter).postDormancy(any(), any(), any(), any());

        assertThatThrownBy(() -> service.setSubStatusDormant(ACCOUNT_ID)).isInstanceOf(SynapsePostingException.class)
                .hasMessage("posting failed");

        verify(account, never()).setSubStatusDormant();
        verify(savingAccountRepositoryWrapper, never()).saveAndFlush(any(SavingsAccount.class));
        verifyNoInteractions(journalEntryWritePlatformService);
    }

    @Test
    void escheat_propagatesAndDoesNotMutate_whenOutboxThrows() {
        enableSynapse();
        doThrow(new SynapsePostingException("posting failed")).when(outboxWriter).postDormancy(any(), any(), any(), any());

        assertThatThrownBy(() -> service.escheat(ACCOUNT_ID)).isInstanceOf(SynapsePostingException.class).hasMessage("posting failed");

        verify(account, never()).escheat(any());
        verify(savingAccountRepositoryWrapper, never()).saveAndFlush(any(SavingsAccount.class));
        verifyNoInteractions(journalEntryWritePlatformService);
    }

    @Test
    void setSubStatusInactive_usesFallbackReason_whenThresholdNull() {
        enableSynapse();
        when(savingsProduct.getDaysToInactive()).thenReturn(null);

        service.setSubStatusInactive(ACCOUNT_ID);

        verify(outboxWriter).postDormancy(eq(account), eq(SavingsAccountSubStatusEnum.INACTIVE), eq(BUSINESS_DATE),
                eq("Threshold reached"));
    }

    @Test
    void setSubStatusDormant_usesFallbackReason_whenThresholdNull() {
        enableSynapse();
        when(savingsProduct.getDaysToDormancy()).thenReturn(null);

        service.setSubStatusDormant(ACCOUNT_ID);

        verify(outboxWriter).postDormancy(eq(account), eq(SavingsAccountSubStatusEnum.DORMANT), eq(BUSINESS_DATE), eq("Threshold reached"));
    }

    @Test
    void escheat_usesFallbackReason_whenThresholdNull() {
        enableSynapse();
        when(savingsProduct.getDaysToEscheat()).thenReturn(null);

        service.escheat(ACCOUNT_ID);

        verify(outboxWriter).postDormancy(eq(account), eq(SavingsAccountSubStatusEnum.ESCHEAT), eq(BUSINESS_DATE), eq("Threshold reached"));
    }

    private void assertJournalEntryPostedFor(Long expectedSavingsId) {
        ArgumentCaptor<SavingsAccountingBridgeDTO> bridgeCaptor = ArgumentCaptor.forClass(SavingsAccountingBridgeDTO.class);
        verify(journalEntryWritePlatformService).createJournalEntriesForSavings(bridgeCaptor.capture());
        assertThat(bridgeCaptor.getValue().getSavingsId()).isEqualTo(expectedSavingsId);
    }

    @SuppressWarnings("unchecked")
    private void enableSynapse() {
        when(synapseDormancyPostingOutboxWriterProvider.getIfAvailable()).thenReturn(outboxWriter);
        when(synapseDormancyPostingOutboxWriterProvider.getObject()).thenReturn(outboxWriter);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(true);
    }

    @SuppressWarnings("restriction")
    private SavingsAccountWritePlatformServiceJpaRepositoryImpl createServiceWithReflection() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        SavingsAccountWritePlatformServiceJpaRepositoryImpl svc = (SavingsAccountWritePlatformServiceJpaRepositoryImpl) unsafe
                .allocateInstance(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class);
        setField(svc, "savingAccountAssembler", savingAccountAssembler);
        setField(svc, "savingAccountRepositoryWrapper", savingAccountRepositoryWrapper);
        setField(svc, "synapseDormancyPostingOutboxWriterProvider", synapseDormancyPostingOutboxWriterProvider);
        setField(svc, "configurationDomainService", configurationDomainService);
        setField(svc, "appuserRepository", appuserRepository);
        setField(svc, "journalEntryWritePlatformService", journalEntryWritePlatformService);
        return svc;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = SavingsAccountWritePlatformServiceJpaRepositoryImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
