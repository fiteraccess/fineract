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

import org.apache.fineract.cob.COBBusinessStep;
import org.apache.fineract.cob.eod.EodBusinessStep;
import org.apache.fineract.portfolio.eod.jobs.EodCloseOfBusinessTasklet;
import org.springframework.stereotype.Service;

/** The Access end-of-day close of business, a category beside upstream's loan and working capital loan ones. */
@Service
public class EodBusinessStepCategoryServiceImpl implements BusinessStepCategoryService {

    @Override
    public BusinessStepCategory getCategory() {
        return BusinessStepCategory.EOD;
    }

    @Override
    public String getCobJobName() {
        return EodCloseOfBusinessTasklet.JOB_NAME;
    }

    @Override
    public Class<? extends COBBusinessStep<?>> getBusinessStepClass() {
        return EodBusinessStep.class;
    }
}
