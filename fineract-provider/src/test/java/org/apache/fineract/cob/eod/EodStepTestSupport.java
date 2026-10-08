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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.service.EodRunService;

/** What every step test needs: a tenant context, fast polling, a run for D, and a checkpoint service that answers. */
final class EodStepTestSupport {

    static final LocalDate D = LocalDate.of(2026, 10, 6);
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 18, 0);

    private EodStepTestSupport() {}

    static void tenantContext() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, D, BusinessDateType.COB_DATE, D.minusDays(1))));
    }

    /** No waiting in tests: zero poll interval and zero grace; the timeout is a minute unless a test shortens it. */
    static FineractProperties fastProperties() {
        return properties(1);
    }

    /** A zero timeout trips on the first unfinished poll. */
    static FineractProperties properties(int stepTimeoutMinutes) {
        FineractProperties properties = new FineractProperties();
        FineractProperties.FineractEodProperties eod = new FineractProperties.FineractEodProperties();
        eod.setPollIntervalSeconds(0);
        eod.setReplayGraceSeconds(0);
        eod.setStepTimeoutMinutes(stepTimeoutMinutes);
        properties.setEod(eod);
        return properties;
    }

    static EodRun run() {
        return EodRun.start(D, NOW);
    }

    /** The checkpoint service hands every step a fresh RUNNING checkpoint and knows no other step yet. */
    static void freshCheckpoints(EodRunService runService, EodRun run) {
        when(runService.beginStep(any(EodRun.class), anyString()))
                .thenAnswer(invocation -> EodStep.start(invocation.getArgument(0), invocation.getArgument(1), NOW));
        when(runService.findStep(any(EodRun.class), anyString())).thenReturn(Optional.empty());
    }

    static EodStep completedStep(EodRun run, String name, LocalDateTime completedAt) {
        EodStep step = EodStep.start(run, name, completedAt.minusMinutes(1));
        step.setStatus(EodStep.STATUS_COMPLETED);
        step.setCompletedAt(completedAt);
        return step;
    }
}
