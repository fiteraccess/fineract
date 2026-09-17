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
package org.apache.fineract.portfolio.savings.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.savings.jobs.dormancygrace.ExpireDormancyGraceWindowsTasklet;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
class ExpireDormancyGraceWindowsTaskletTest {

    @Mock
    private SavingsAccountReadPlatformService readPlatformService;

    @Mock
    private SavingsAccountWritePlatformService writePlatformService;

    private ExpireDormancyGraceWindowsTasklet tasklet;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        tasklet = new ExpireDormancyGraceWindowsTasklet(readPlatformService, writePlatformService);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void proposesARevertForEveryLapsedAccount() throws Exception {
        when(readPlatformService.retrieveSavingsIdsWithLapsedDormancyGrace(any(LocalDateTime.class))).thenReturn(List.of(11L, 22L));

        RepeatStatus status = tasklet.execute(null, null);

        verify(writePlatformService).revertLapsedDormancyGrace(11L);
        verify(writePlatformService).revertLapsedDormancyGrace(22L);
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void doesNothingWhenNoWindowHasLapsed() throws Exception {
        when(readPlatformService.retrieveSavingsIdsWithLapsedDormancyGrace(any(LocalDateTime.class))).thenReturn(List.of());

        tasklet.execute(null, null);

        verify(writePlatformService, never()).revertLapsedDormancyGrace(any());
    }

    @Test
    void toleratesANullResultRatherThanFailingTheRun() throws Exception {
        when(readPlatformService.retrieveSavingsIdsWithLapsedDormancyGrace(any(LocalDateTime.class))).thenReturn(null);

        tasklet.execute(null, null);

        verify(writePlatformService, never()).revertLapsedDormancyGrace(any());
    }
}
