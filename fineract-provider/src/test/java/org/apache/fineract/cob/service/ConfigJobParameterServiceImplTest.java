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
package org.apache.fineract.cob.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;
import org.apache.fineract.cob.COBBusinessStep;
import org.apache.fineract.cob.data.BusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.cob.eod.EodBusinessStep;
import org.apache.fineract.cob.eod.EodStepConfigValidator;
import org.apache.fineract.cob.eod.EodStepPlan;
import org.apache.fineract.cob.exceptions.BusinessStepException;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.eod.jobs.EodCloseOfBusinessTasklet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

/** A job's own validator vets a step configuration before the stored one is replaced; other jobs are untouched. */
class ConfigJobParameterServiceImplTest {

    private static final String LOAN_JOB = "LOAN_CLOSE_OF_BUSINESS";
    private static final String LOAN_STEP = "APPLY_CHARGE_TO_OVERDUE_LOANS";

    private BatchBusinessStepRepository repository;
    private BusinessStepConfigDataParser parser;
    private BusinessStepCategoryService categories;
    private ApplicationContext context;
    private JsonCommand command;
    private ConfigJobParameterServiceImpl underTest;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(BatchBusinessStepRepository.class);
        parser = mock(BusinessStepConfigDataParser.class);
        categories = mock(BusinessStepCategoryService.class);
        context = mock(ApplicationContext.class);
        command = mock(JsonCommand.class);
        ObjectProvider<BusinessStepConfigValidator> validators = mock(ObjectProvider.class);
        when(validators.orderedStream()).thenAnswer(invocation -> Stream.of(new EodStepConfigValidator()));
        underTest = new ConfigJobParameterServiceImpl(repository, parser, categories, context, mock(BusinessStepMapper.class), validators);

        List<String> eodNames = EodStepPlan.REQUIRED;
        when(categories.getBusinessStepByCategory(EodCloseOfBusinessTasklet.JOB_NAME)).thenReturn((Class) EodBusinessStep.class);
        when(context.getBeanNamesForType(EodBusinessStep.class)).thenReturn(eodNames.toArray(String[]::new));
        eodNames.forEach(name -> stepBean(name, name));
        when(categories.getBusinessStepByCategory(LOAN_JOB)).thenReturn((Class) COBBusinessStep.class);
        when(context.getBeanNamesForType(COBBusinessStep.class)).thenReturn(new String[] { "loanStep" });
        stepBean("loanStep", LOAN_STEP);
    }

    @Test
    void anEodConfigurationMissingRequiredStepsIsRefusedAndTheStoredOneKept() {
        when(parser.parseUpdate(command)).thenReturn(List.of(step("EOD_ROLLOVER", 1L), step("EOD_COMPLETION", 9L)));

        BusinessStepException thrown = assertThrows(BusinessStepException.class,
                () -> underTest.updateStepConfigByJobName(command, EodCloseOfBusinessTasklet.JOB_NAME));

        assertTrue(thrown.getMessage().startsWith("invalid EOD step configuration: required steps missing"));
        verify(repository, never()).deleteAllByJobName(any());
        verify(repository, never()).save(any());
    }

    @Test
    void aLoanJobConfigurationIsSavedAsBefore() {
        when(parser.parseUpdate(command)).thenReturn(List.of(step(LOAN_STEP, 1L)));

        underTest.updateStepConfigByJobName(command, LOAN_JOB);

        verify(repository).deleteAllByJobName(LOAN_JOB);
        verify(repository).save(any());
    }

    private void stepBean(String beanName, String enumStyledName) {
        COBBusinessStep<?> bean = mock(COBBusinessStep.class);
        when(bean.getEnumStyledName()).thenReturn(enumStyledName);
        when(context.getBean(beanName)).thenReturn(bean);
    }

    private static BusinessStep step(String name, Long order) {
        BusinessStep step = new BusinessStep();
        step.setStepName(name);
        step.setOrder(order);
        return step;
    }
}
