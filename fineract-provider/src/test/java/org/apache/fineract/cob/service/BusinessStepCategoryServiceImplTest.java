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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.fineract.cob.eod.EodBusinessStep;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.junit.jupiter.api.Test;

/** A category is named by itself or by a job name that starts with it, so the steps API and the step rows agree. */
class BusinessStepCategoryServiceImplTest {

    private final BusinessStepCategoryServiceImpl underTest = new BusinessStepCategoryServiceImpl();

    @Test
    void loanStepsByCategoryOrJobName() {
        assertEquals(LoanCOBBusinessStep.class, underTest.getBusinessStepByCategory("LOAN"));
        assertEquals(LoanCOBBusinessStep.class, underTest.getBusinessStepByCategory("loan"));
        assertEquals(LoanCOBBusinessStep.class, underTest.getBusinessStepByCategory("LOAN_CLOSE_OF_BUSINESS"));
    }

    @Test
    void eodStepsByCategoryOrJobName() {
        assertEquals(EodBusinessStep.class, underTest.getBusinessStepByCategory("EOD"));
        assertEquals(EodBusinessStep.class, underTest.getBusinessStepByCategory("EOD_CLOSE_OF_BUSINESS"));
    }

    @Test
    void anythingElseHasNoSteps() {
        assertNull(underTest.getBusinessStepByCategory("SAVINGS_CLOSE_OF_BUSINESS"));
        assertNull(underTest.getBusinessStepByCategory("LOANS"));
        assertNull(underTest.getBusinessStepByCategory(null));
    }
}
