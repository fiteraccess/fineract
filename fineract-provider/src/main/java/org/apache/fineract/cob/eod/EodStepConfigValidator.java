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

import java.util.List;
import org.apache.fineract.cob.data.BusinessStep;
import org.apache.fineract.cob.exceptions.BusinessStepException;
import org.apache.fineract.cob.service.BusinessStepConfigValidator;
import org.apache.fineract.portfolio.eod.jobs.EodCloseOfBusinessTasklet;
import org.springframework.stereotype.Component;

/** Refuses an EOD step configuration that {@link EodStepPlan} would not run. */
@Component
public class EodStepConfigValidator implements BusinessStepConfigValidator {

    @Override
    public boolean appliesTo(String jobName) {
        return EodCloseOfBusinessTasklet.JOB_NAME.equals(jobName);
    }

    @Override
    public void validate(List<BusinessStep> steps) {
        List<String> problems = EodStepPlan
                .problems(steps.stream().map(step -> new EodStepPlan.Entry(step.getStepName(), step.getOrder())).toList());
        if (!problems.isEmpty()) {
            throw new BusinessStepException(EodStepPlan.describe(problems));
        }
    }
}
