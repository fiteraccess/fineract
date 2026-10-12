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
package org.apache.fineract.accounting.glaccount.service;

import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;

/**
 * How a balance read treats reversals. {@code EXCLUDE} (the default) leaves out both the reversed entry and the
 * reversal posted against it, so a reversal reads as if neither posting happened. {@code AS_POSTED} counts every entry
 * on its own {@code entry_date}: the original on its day and the contra entry on the reversal's day, which is what a
 * caller comparing day-by-day movements with another ledger needs.
 */
public enum GLAccountReversals {

    EXCLUDE("exclude"), AS_POSTED("asPosted");

    public static final String PARAM_NAME = "reversals";

    private final String paramValue;

    GLAccountReversals(final String paramValue) {
        this.paramValue = paramValue;
    }

    public String paramValue() {
        return this.paramValue;
    }

    /** Absent or blank means {@link #EXCLUDE}; anything other than the two values is a validation error. */
    public static GLAccountReversals fromParam(final String value) {
        if (StringUtils.isBlank(value)) {
            return EXCLUDE;
        }
        for (final GLAccountReversals mode : values()) {
            if (mode.paramValue.equals(value.trim())) {
                return mode;
            }
        }
        final ApiParameterError error = ApiParameterError.parameterError("validation.msg.glaccount.balance.reversals.invalid",
                "The parameter `reversals` must be `exclude` or `asPosted`.", PARAM_NAME, value);
        throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.", List.of(error));
    }
}
