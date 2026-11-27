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

import java.util.Locale;
import org.apache.fineract.cob.COBBusinessStep;

public interface BusinessStepCategoryService extends BusinessStepCategoryResolver {

    BusinessStepCategory getCategory();

    String getCobJobName();

    Class<? extends COBBusinessStep<?>> getBusinessStepClass();

    /**
     * A category answers to its own name (in any case), to its COB job name, and to any job name that starts with the
     * category name followed by an underscore.
     */
    @Override
    default Class<? extends COBBusinessStep<?>> getBusinessStepByCategory(final String name) {
        if (name == null) {
            return null;
        }
        final String categoryName = getCategory().name();
        final String upperName = name.toUpperCase(Locale.ROOT);
        final boolean claimed = upperName.equals(categoryName) || upperName.startsWith(categoryName + "_") || name.equals(getCobJobName());
        return claimed ? getBusinessStepClass() : null;
    }
}
