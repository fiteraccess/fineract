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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseChargePostingOutboxWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import sun.misc.Unsafe;

class SavingsAccountWritePlatformServiceApplyChargeDueTest {

    private static final Long CHARGE_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long OFFICE_ID = 5L;
    private static final String EXTERNAL_ID_VALUE = "EXT-123";
    private static final String CURRENCY_CODE = "NGN";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 4, 18);
    private static final LocalDate DUE_DATE_BEFORE_BUSINESS = LocalDate.of(2026, 1, 1);

    private SavingsAccountWritePlatformServiceJpaRepositoryImpl service;

    private SavingsAccountChargeRepositoryWrapper chargeRepository;
    private ConfigurationDomainService configurationDomainService;
    @SuppressWarnings("rawtypes")
    private ObjectProvider synapseChargePostingOutboxWriterProvider;
    private SynapseChargePostingOutboxWriter outboxWriter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(
                BusinessDateType.BUSINESS_DATE, BUSINESS_DATE,
                BusinessDateType.COB_DATE, LocalDate.of(2026, 4, 17))));

        chargeRepository = mock(SavingsAccountChargeRepositoryWrapper.class);
        configurationDomainService = mock(ConfigurationDomainService.class);
        synapseChargePostingOutboxWriterProvider = mock(ObjectProvider.class);
        outboxWriter = mock(SynapseChargePostingOutboxWriter.class);
        service = createServiceWithReflection();
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Nested
    class SynapseRouting {

        @Test
        void routesToSynapse_whenEnabledAndFlagTrue() {
            SavingsAccountCharge charge = buildCharge("Outbound Transfer Fee", true);
            when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID, ACCOUNT_ID)).thenReturn(charge);
            enableSynapse();

            service.applyChargeDue(CHARGE_ID, ACCOUNT_ID);

            verify(outboxWriter).postCharge(
                    eq(CHARGE_ID), eq(ACCOUNT_ID), eq(OFFICE_ID), eq(EXTERNAL_ID_VALUE),
                    eq("Outbound Transfer Fee"),
                    eq(new BigDecimal("100.00")), eq(BUSINESS_DATE), eq(CURRENCY_CODE));
            verify(charge, never()).isNotFullyPaid();
        }

        @Test
        void fallsBackToPayCharge_whenProviderUnavailable() {
            SavingsAccountCharge charge = buildCharge("Outbound Transfer Fee", false);
            when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID, ACCOUNT_ID)).thenReturn(charge);
            when(synapseChargePostingOutboxWriterProvider.getIfAvailable()).thenReturn(null);
            when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(true);

            service.applyChargeDue(CHARGE_ID, ACCOUNT_ID);

            verify(outboxWriter, never()).postCharge(any(), any(), any(), any(), any(), any(), any(), any());
            verify(charge).isNotFullyPaid();
        }

        @Test
        void fallsBackToPayCharge_whenFlagDisabled() {
            SavingsAccountCharge charge = buildCharge("Outbound Transfer Fee", false);
            when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID, ACCOUNT_ID)).thenReturn(charge);
            when(synapseChargePostingOutboxWriterProvider.getIfAvailable()).thenReturn(outboxWriter);
            when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(false);

            service.applyChargeDue(CHARGE_ID, ACCOUNT_ID);

            verify(outboxWriter, never()).postCharge(any(), any(), any(), any(), any(), any(), any(), any());
            verify(charge).isNotFullyPaid();
        }

        @Test
        void fallsBackToPayCharge_whenBothDisabled() {
            SavingsAccountCharge charge = buildCharge("Outbound Transfer Fee", false);
            when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID, ACCOUNT_ID)).thenReturn(charge);
            when(synapseChargePostingOutboxWriterProvider.getIfAvailable()).thenReturn(null);
            when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(false);

            service.applyChargeDue(CHARGE_ID, ACCOUNT_ID);

            verify(outboxWriter, never()).postCharge(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void routesAnyChargeType_whenEnabled() {
            SavingsAccountCharge charge = buildCharge("Monthly Maintenance Fee", true);
            when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID, ACCOUNT_ID)).thenReturn(charge);
            enableSynapse();

            service.applyChargeDue(CHARGE_ID, ACCOUNT_ID);

            verify(outboxWriter).postCharge(
                    eq(CHARGE_ID), eq(ACCOUNT_ID), eq(OFFICE_ID), eq(EXTERNAL_ID_VALUE),
                    eq("Monthly Maintenance Fee"),
                    eq(new BigDecimal("100.00")), eq(BUSINESS_DATE), eq(CURRENCY_CODE));
        }

        @Test
        void skipsProcessing_whenChargeFullyPaid() {
            SavingsAccountCharge charge = buildCharge("Outbound Transfer Fee", false);
            when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID, ACCOUNT_ID)).thenReturn(charge);
            disableSynapse();

            service.applyChargeDue(CHARGE_ID, ACCOUNT_ID);

            verify(outboxWriter, never()).postCharge(any(), any(), any(), any(), any(), any(), any(), any());
            verify(charge).isNotFullyPaid();
        }
    }

    @SuppressWarnings("unchecked")
    private void enableSynapse() {
        when(synapseChargePostingOutboxWriterProvider.getIfAvailable()).thenReturn(outboxWriter);
        when(synapseChargePostingOutboxWriterProvider.getObject()).thenReturn(outboxWriter);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(true);
    }

    private void disableSynapse() {
        when(synapseChargePostingOutboxWriterProvider.getIfAvailable()).thenReturn(null);
        when(configurationDomainService.isSynapseInterestPostingEnabled()).thenReturn(false);
    }

    private SavingsAccountCharge buildCharge(String chargeName, boolean notFullyPaid) {
        Charge chargeDefinition = mock(Charge.class);
        when(chargeDefinition.getName()).thenReturn(chargeName);
        when(chargeDefinition.getCurrencyCode()).thenReturn(CURRENCY_CODE);

        SavingsAccount account = mock(SavingsAccount.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.officeId()).thenReturn(OFFICE_ID);
        when(account.getExternalId()).thenReturn(new ExternalId(EXTERNAL_ID_VALUE));

        SavingsAccountCharge savingsCharge = mock(SavingsAccountCharge.class);
        when(savingsCharge.getCharge()).thenReturn(chargeDefinition);
        when(savingsCharge.savingsAccount()).thenReturn(account);
        when(savingsCharge.amoutOutstanding()).thenReturn(new BigDecimal("100.00"));
        when(savingsCharge.currencyCode()).thenReturn(CURRENCY_CODE);
        when(savingsCharge.isNotFullyPaid()).thenReturn(notFullyPaid);
        when(savingsCharge.getDueDate()).thenReturn(DUE_DATE_BEFORE_BUSINESS);
        return savingsCharge;
    }

    @SuppressWarnings("restriction")
    private SavingsAccountWritePlatformServiceJpaRepositoryImpl createServiceWithReflection() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        SavingsAccountWritePlatformServiceJpaRepositoryImpl svc =
                (SavingsAccountWritePlatformServiceJpaRepositoryImpl) unsafe.allocateInstance(
                        SavingsAccountWritePlatformServiceJpaRepositoryImpl.class);
        setField(svc, "savingsAccountChargeRepository", chargeRepository);
        setField(svc, "configurationDomainService", configurationDomainService);
        setField(svc, "synapseChargePostingOutboxWriterProvider", synapseChargePostingOutboxWriterProvider);
        return svc;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = SavingsAccountWritePlatformServiceJpaRepositoryImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
