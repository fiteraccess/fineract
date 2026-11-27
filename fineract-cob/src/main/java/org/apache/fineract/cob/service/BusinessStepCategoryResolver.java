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

/**
 * Resolves the business step type behind a name, across every category. The name is either a category ({@code LOAN}),
 * or a job name that starts with one ({@code EOD_CLOSE_OF_BUSINESS}), so the steps API and the stored step rows agree.
 */
public interface BusinessStepCategoryResolver {

    /** @return the step type the name stands for, or {@code null} when no category claims it */
    Class<? extends COBBusinessStep<?>> getBusinessStepByCategory(String name);
}
