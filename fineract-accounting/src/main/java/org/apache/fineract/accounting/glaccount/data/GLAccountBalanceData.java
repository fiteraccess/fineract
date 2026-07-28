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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;

/**
 * GL account movements for a window, computed from raw journal entries in a single grouped query.
 *
 * <p>
 * {@code openingBalance} plus the signed net of {@code buckets} gives the closing balance; the caller applies the
 * type-aware sign rule using {@link #type}, or reads {@code totalDebits}/{@code totalCredits} directly to reconcile
 * either way. Monetary fields serialise as JSON strings — see {@link GLAccountDetailsData}.
 */
public record GLAccountBalanceData(@Schema(example = "12") Long glAccountId, @Schema(example = "10101") String glCode,
        @Schema(example = "Cash at Main Vault") String name,
        @Schema(description = "GL classification. `value` is one of ASSET, LIABILITY, EQUITY, INCOME, EXPENSE.") EnumOptionData type,
        @Schema(description = "Echo of the `officeId` filter. Null means organisation-wide.", example = "1") Long officeId,
        @Schema(description = "Echo of the `currencyCode` filter. Null means all currencies were summed.", example = "NGN") String currencyCode,
        @Schema(description = "Inclusive window start, as resolved.", example = "2026-07-01") LocalDate fromDate,
        @Schema(description = "Inclusive window end, as resolved.", example = "2026-07-31") LocalDate toDate,
        GLAccountBalanceGranularity granularity,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = """
                Type-aware signed balance over every entry strictly before `fromDate`. Zero when there are none.""", example = "1000.000000") BigDecimal openingBalance,
        @Schema(description = """
                For `granularity=PERIOD`, exactly one bucket spanning the window — present even when there was no
                movement, in which case both totals are zero. For `granularity=DAILY`, one bucket per day that HAS
                movement, ascending by date; days with no entries are absent and the caller zero-fills them.""") List<GLAccountBalanceBucketData> buckets) {
}
