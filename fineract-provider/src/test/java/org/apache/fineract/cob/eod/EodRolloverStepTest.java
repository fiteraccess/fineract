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
package org.apache.fineract.cob.eod;

import static org.apache.fineract.cob.eod.EodStepTestSupport.D;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBusinessDateResponse;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EodRolloverStepTest {

    private EodRunService runService;
    private ConfigurationDomainService configuration;
    private BusinessDateReadPlatformService businessDateRead;
    private BusinessDateWritePlatformService businessDateWrite;
    private SynapseTransactionClient synapse;
    private EodRolloverStep underTest;
    private EodRun run;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EodStepTestSupport.tenantContext();
        runService = mock(EodRunService.class);
        configuration = mock(ConfigurationDomainService.class);
        businessDateRead = mock(BusinessDateReadPlatformService.class);
        businessDateWrite = mock(BusinessDateWritePlatformService.class);
        synapse = mock(SynapseTransactionClient.class);
        ObjectProvider<SynapseTransactionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(synapse);
        underTest = new EodRolloverStep(runService, EodStepTestSupport.fastProperties(), configuration, businessDateRead, businessDateWrite,
                provider);
        run = EodStepTestSupport.run();
        EodStepTestSupport.freshCheckpoints(runService, run);
        when(configuration.isBusinessDateEnabled()).thenReturn(true);
        when(configuration.isCOBDateAdjustmentEnabled()).thenReturn(true);
        fineractIsOn(D);
        SynapseBusinessDateResponse pushed = new SynapseBusinessDateResponse();
        pushed.setBusinessDate(D.plusDays(1));
        when(synapse.postBusinessDate(D.plusDays(1))).thenReturn(pushed);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void advancesFineractFirst_thenPushesSynapse_andClosesDInTheRunsContext() throws Exception {
        underTest.execute(run);

        verify(businessDateWrite).increaseDateByTypeByOneDay(BusinessDateType.BUSINESS_DATE);
        verify(synapse).postBusinessDate(D.plusDays(1));
        assertEquals(D.plusDays(1), ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
        assertEquals(D, ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE));
        verify(runService).completed(any(), any());
        verify(runService, never()).recordException(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aResumedRunFindsFineractAlreadyOnTheNextDay_andOnlyRepeatsThePush() throws Exception {
        fineractIsOn(D.plusDays(1));

        underTest.execute(run);

        verify(businessDateWrite, never()).increaseDateByTypeByOneDay(any());
        verify(synapse).postBusinessDate(D.plusDays(1));
    }

    @Test
    void refusesWhenFineractIsOnSomeOtherDay() throws Exception {
        fineractIsOn(D.minusDays(1));

        EodStepFailedException thrown = assertThrows(EodStepFailedException.class, () -> underTest.execute(run));

        assertEquals(EodRolloverStep.CODE_PREFLIGHT, thrown.getCode());
        verify(businessDateWrite, never()).increaseDateByTypeByOneDay(any());
        verify(runService).failed(any(), eq(EodRolloverStep.CODE_PREFLIGHT), anyString());
    }

    @Test
    void refusesWhenTheBusinessDateFeatureIsOff() {
        when(configuration.isBusinessDateEnabled()).thenReturn(false);

        assertEquals(EodRolloverStep.CODE_PREFLIGHT, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    void aFineractRefusalFailsTheStep() throws Exception {
        org.mockito.Mockito.doThrow(new JobExecutionException(java.util.List.of(new RuntimeException("closed")))).when(businessDateWrite)
                .increaseDateByTypeByOneDay(BusinessDateType.BUSINESS_DATE);

        assertEquals(EodRolloverStep.CODE_ROLLOVER, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
        verify(synapse, never()).postBusinessDate(any());
    }

    @Test
    void aCobDateThatWillNotFollowIsAnExceptionRow_notAFailure() {
        when(configuration.isCOBDateAdjustmentEnabled()).thenReturn(false);

        underTest.execute(run);

        verify(runService).recordException(eq(run), eq(EodRolloverStep.NAME), eq(EodException.SEVERITY_WARN),
                eq(EodRolloverStep.CODE_COB_DATE_NOT_ADJUSTED), anyString(), any());
        verify(runService).completed(any(), any());
    }

    @Test
    void aRefusedPushFailsTheStepAfterFineractAdvanced() {
        when(synapse.postBusinessDate(D.plusDays(1))).thenThrow(new SynapsePostingException("HTTP 409 ahead of Fineract", null));

        assertEquals(EodRolloverStep.CODE_SYNAPSE_PUSH, assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutSynapseTheChainCannotRun() {
        ObjectProvider<SynapseTransactionClient> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        underTest = new EodRolloverStep(runService, EodStepTestSupport.fastProperties(), configuration, businessDateRead, businessDateWrite,
                absent);

        assertEquals(AbstractEodStep.CODE_SYNAPSE_DISABLED,
                assertThrows(EodStepFailedException.class, () -> underTest.execute(run)).getCode());
    }

    private void fineractIsOn(LocalDate businessDate) {
        when(businessDateRead.getBusinessDates())
                .thenReturn(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, businessDate, BusinessDateType.COB_DATE, businessDate.minusDays(1))));
    }
}
