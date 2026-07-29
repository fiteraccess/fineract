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
import org.apache.fineract.infrastructure.core.data.EnumOptionData;

/**
 * GL account movements for the inclusive window {@code [fromDate, toDate]}, computed from raw journal entries in a
 * single query. One flat aggregate row — the caller supplies the exact window it wants (there is no granularity concept
 * at this layer; a caller wanting a daily, month-to-date or year-to-date view resolves that window itself before
 * calling this endpoint).
 *
 * <p>
 * {@code closingBalance} equals {@code openingBalance} plus the signed {@code netMovement}; {@code totalDebits} and
 * {@code totalCredits} are the raw unsigned sums, so a caller can reconcile either sign convention. Monetary fields
 * serialise as JSON strings — see {@link GLAccountDetailsData}.
 */
public record GLAccountBalanceData(@Schema(example = "12") Long glAccountId, @Schema(example = "10101") String glCode,
        @Schema(example = "Cash at Main Vault") String name,
        @Schema(description = "GL classification. `value` is one of ASSET, LIABILITY, EQUITY, INCOME, EXPENSE.") EnumOptionData type,
        @Schema(description = "Echo of the `officeId` filter. Null means organisation-wide.", example = "1") Long officeId,
        @Schema(description = """
                The single currency posted to this account within the window, derived from journal entries. Null when
                there was no movement in the window. Echoes `currencyCode` when it was supplied; otherwise 409 if more
                than one currency was posted in the window.""", example = "NGN") String currency,
        @Schema(description = "Inclusive window start, as supplied.", example = "2026-07-01") LocalDate fromDate,
        @Schema(description = "Inclusive window end, as supplied.", example = "2026-07-31") LocalDate toDate,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = """
                Type-aware signed balance over every non-reversed entry strictly before `fromDate`. Zero when there are
                none.""", example = "1000.000000") BigDecimal openingBalance,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = "Raw unsigned sum of non-reversed debit entries in the window.", example = "500.000000") BigDecimal totalDebits,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = "Raw unsigned sum of non-reversed credit entries in the window.", example = "250.000000") BigDecimal totalCredits,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = "Signed movement across the window, sided by GL account type.", example = "250.000000") BigDecimal netMovement,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = "`openingBalance` plus `netMovement`.", example = "1250.000000") BigDecimal closingBalance,
        @Schema(description = "Non-reversed journal entry rows in the window.", example = "17") Long entryCount) {
}
