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
package org.apache.fineract.portfolio.savings;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiFacingEnum;

/**
 * What a savings product is used for. Synapse reads it to decide goal and autosave provisioning and product type, and
 * the interest-posting job skips GOAL products because goals post interest only at settlement.
 */
@Getter
@RequiredArgsConstructor
public enum SavingsProductCategory implements ApiFacingEnum<SavingsProductCategory> {

    GOAL("savingsProductCategory.goal", "Goal"), //
    AUTOSAVE("savingsProductCategory.autosave", "AutoSave"), //
    DIGITAL("savingsProductCategory.digital", "Digital"), //
    ;

    private final String code;
    private final String humanReadableName;

    /** GOAL and AUTOSAVE are each provisioned onto exactly one product, so at most one product may hold them. */
    public boolean isSingleProduct() {
        return this == GOAL || this == AUTOSAVE;
    }
}
