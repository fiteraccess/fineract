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
 * A GL account resolved by its {@code gl_code}, together with a live balance derived from raw journal entries.
 *
 * <p>
 * Monetary fields serialise as JSON <b>strings</b>, not numbers. Consumers that parse JSON numbers into a binary
 * floating-point type would silently lose precision on a bank-wide control account: the underlying column is
 * {@code decimal(19,6)}, whose full range does not fit in a {@code double}. Since the whole point of this endpoint is
 * an exact balance, the wire format must not be the lossy one.
 */
public record GLAccountDetailsData(@Schema(example = "12") Long id, @Schema(example = "10101") String glCode,
        @Schema(example = "Cash at Main Vault") String name,
        @Schema(description = "GL classification. `value` is one of ASSET, LIABILITY, EQUITY, INCOME, EXPENSE.") EnumOptionData type,
        @Schema(description = "DETAIL or HEADER. A HEADER account reports only entries posted directly to it; children are not rolled up.") EnumOptionData usage,
        @Schema(example = "false") Boolean disabled, @Schema(example = "true") Boolean manualEntriesAllowed, String description,
        @Schema(description = "Immediate parent only, one level. Null when this is a root account.", example = "11") Long parentId,
        @Schema(example = "101") String parentGlCode, @Schema(example = "Current Assets") String parentName,
        @Schema(description = "The as-of date the balance was computed at: either the supplied `asOnDate` or the current business date.") LocalDate asOnDate,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = """
                Type-aware signed balance over every entry up to and including `asOnDate`, at full stored precision.
                ASSET and EXPENSE accounts increase on debit; LIABILITY, EQUITY and INCOME increase on credit.""", example = "10450000.550000") BigDecimal balance,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = "Raw unsigned sum of debit entries up to `asOnDate`.", example = "20450000.550000") BigDecimal totalDebits,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", description = "Raw unsigned sum of credit entries up to `asOnDate`.", example = "10000000.000000") BigDecimal totalCredits,
        @Schema(description = """
                The latest `entry_date` over ALL entries, deliberately not capped at `asOnDate`, so a forward-dated
                posting is still visible. Null when the account has never been posted to.""", example = "2026-07-27") LocalDate lastMovementDate,
        @Schema(description = """
                Distinct currency codes posted to this account up to `asOnDate`, ascending. Fineract does not model a
                currency on a GL account, so this is derived from journal entries. Empty when never posted to; more than
                one entry means `balance`, `totalDebits` and `totalCredits` mix currencies unless `currencyCode` was
                supplied.""", example = "[\"NGN\"]") List<String> currencies,
        @Schema(description = "Journal entry rows counted up to `asOnDate`. Reversals and their counterparts are both included.", example = "482") Long entryCount,
        @Schema(description = "Echo of the `officeId` filter. Null means organisation-wide.", example = "1") Long officeId,
        @Schema(description = "Echo of the `currencyCode` filter. Null means all currencies were summed.", example = "NGN") String currencyCode) {
}
