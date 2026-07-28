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
package org.apache.fineract.accounting.glaccount.data;

import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;

/**
 * Bucketing mode for {@code GET /v1/glaccounts/code/{glCode}/balance}.
 *
 * <p>
 * Deliberately does <i>not</i> offer month-to-date or year-to-date. Those are anchored to a fiscal calendar, and
 * Fineract owns no fiscal-calendar policy for reporting windows — {@code financial-year-beginning-month} is read only
 * by savings interest-posting code. A caller wanting MTD/YTD resolves the window itself and asks for {@link #PERIOD},
 * so a caller-side bug that forgets to derive the window fails loudly here instead of returning a plausible-but-wrong
 * answer.
 */
public enum GLAccountBalanceGranularity {

    /** One aggregate bucket spanning the whole requested window. */
    PERIOD,

    /** One bucket per {@code entry_date} that has movement. Days with no entries are absent, not zero-filled. */
    DAILY;

    public static GLAccountBalanceGranularity fromQueryParam(final String value) {
        if (StringUtils.isBlank(value)) {
            return PERIOD;
        }
        final String candidate = value.trim();
        for (final GLAccountBalanceGranularity granularity : values()) {
            if (granularity.name().equalsIgnoreCase(candidate)) {
                return granularity;
            }
        }
        throw new UnrecognizedQueryParamException("granularity", value, (Object[]) values());
    }
}
