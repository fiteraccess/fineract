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

import java.util.List;
import org.apache.fineract.cob.COBBusinessStep;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * The one resolver the step configuration service asks: it tries each category in turn. The categories themselves are
 * the per-category {@link BusinessStepCategoryService} beans; they hold no state, so this class keeps its own instances
 * and needs no wiring.
 */
@Service
@Primary
public class BusinessStepCategoryServiceImpl implements BusinessStepCategoryResolver {

    private static final List<BusinessStepCategoryService> CATEGORIES = List.of(new LoanBusinessStepCategoryServiceImpl(),
            new WorkingCapitalLoanBusinessStepCategoryServiceImpl(), new EodBusinessStepCategoryServiceImpl());

    @Override
    public Class<? extends COBBusinessStep<?>> getBusinessStepByCategory(final String name) {
        for (final BusinessStepCategoryService category : CATEGORIES) {
            final Class<? extends COBBusinessStep<?>> businessStepClass = category.getBusinessStepByCategory(name);
            if (businessStepClass != null) {
                return businessStepClass;
            }
        }
        return null;
    }
}
