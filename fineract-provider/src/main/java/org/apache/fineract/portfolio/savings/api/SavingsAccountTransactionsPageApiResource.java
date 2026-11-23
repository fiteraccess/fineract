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
package org.apache.fineract.portfolio.savings.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.service.search.SavingsAccountTransactionPageService;
import org.springframework.stereotype.Component;

/**
 * Paged read of a savings account's transactions — the replacement for loading them through {@code GET
 * /v1/savingsaccounts/{id}?associations=all}. Kept in its own root resource (rather than a method on
 * {@link SavingsAccountTransactionsApiResource}) so the fork does not edit the community resource; JAX-RS prefers the
 * root path with more literal characters, so {@code /transactions/page} is never captured by
 * {@code /transactions/{transactionId}}. See {@link SavingsAccountTransactionPageService} for why it is cheaper than
 * {@code /transactions/search}.
 */
@Path("/v1/savingsaccounts/{savingsId}/transactions/page")
@Component
@Tag(name = "Savings Account Transactions", description = "")
@RequiredArgsConstructor
public class SavingsAccountTransactionsPageApiResource {

    private final SavingsAccountTransactionPageService pageService;
    private final DefaultToApiJsonSerializer<SavingsAccountTransactionData> toApiJsonSerializer;

    @GET
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve one page of a savings account's transactions", description = "Keyset-paged, no total count: returns "
            + "{pageItems, hasMore, nextCursor}; pass nextCursor back as cursor (with the same filters and sortOrder) for the next page. "
            + "Items have the same shape as the transactions of GET /savingsaccounts/{id}?associations=all, ordered by transaction "
            + "date then id.")
    @ApiResponse(responseCode = "200", description = "OK")
    public String retrievePage(@PathParam("savingsId") @Parameter(description = "savings account id") final Long savingsId,
            @QueryParam("cursor") @Parameter(description = "nextCursor of the previous page; omit for the first page") final String cursor,
            @QueryParam("limit") @Parameter(description = "page size, default 50, max 500") final Integer limit,
            @QueryParam("types") @Parameter(description = "only these transaction type ids", example = "1,2") final String types,
            @QueryParam("excludeTypes") @Parameter(description = "exclude these transaction type ids (10 = accrual)", example = "10") final String excludeTypes,
            @QueryParam("reversed") @Parameter(description = "true: only reversed; false: only live; omitted: both") final Boolean reversed,
            @QueryParam("reversal") @Parameter(description = "true: only reversal contras; false: no contras; omitted: both") final Boolean reversal,
            @QueryParam("sortOrder") @Parameter(description = "DESC (default, newest first) or ASC") final String sortOrder) {

        final SavingsAccountTransactionPageService.PageRequest request = new SavingsAccountTransactionPageService.PageRequest(cursor, limit,
                parseTypeIds("types", types), parseTypeIds("excludeTypes", excludeTypes), reversed, reversal, sortOrder);
        return this.toApiJsonSerializer.serialize(this.pageService.retrievePage(savingsId, request));
    }

    static List<Integer> parseTypeIds(final String parameter, final String csv) {
        if (StringUtils.isBlank(csv)) {
            return null;
        }
        final List<Integer> ids = new ArrayList<>();
        for (String token : csv.split(",")) {
            final String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException e) {
                final List<ApiParameterError> errors = new ArrayList<>();
                new DataValidatorBuilder(errors).resource("savingsaccount.transactions.page").parameter(parameter).value(trimmed)
                        .failWithCode("not.a.number");
                throw new PlatformApiDataValidationException(errors, e);
            }
        }
        return ids;
    }
}
