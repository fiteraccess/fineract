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
package org.apache.fineract.accounting.aggregatoraccounting.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.aggregatoraccounting.data.AggregatorAccountingConfigurationData;
import org.apache.fineract.accounting.aggregatoraccounting.data.AggregatorAccountingConfigurationRequest;
import org.apache.fineract.accounting.aggregatoraccounting.service.AggregatorAccountingConfigurationService;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/aggregator-accounting-configurations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Component
@Tag(name = "Aggregator Accounting Configurations", description = "Tenant-scoped GL routing for bills payment and airtime "
        + "aggregators (e.g. CoralPay, Nomiworld). Every configuration is always outbound (the customer pays); the payable "
        + "and commission income accounts are required, and the convenience fee income account is optional and falls back "
        + "to the commission income account when omitted.")
@RequiredArgsConstructor
public class AggregatorAccountingConfigurationsApiResource {

    private final PlatformSecurityContext context;
    private final AggregatorAccountingConfigurationService service;
    private final DefaultToApiJsonSerializer<AggregatorAccountingConfigurationRequest> requestSerializer;
    private final PortfolioCommandSourceWritePlatformService commandSourceService;

    @GET
    @Operation(summary = "List aggregator accounting configurations", operationId = "retrieveAllAggregatorAccountingConfigurations", description = "Lists the current tenant's normalized aggregator-to-GL mappings.")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AggregatorAccountingConfigurationData.class))))
    public List<AggregatorAccountingConfigurationData> retrieveAll() {
        context.authenticatedUser().validateHasReadPermission(AggregatorAccountingConfigurationApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return service.retrieveAll();
    }

    @GET
    @Path("{aggregatorCode}")
    @Operation(summary = "Retrieve an aggregator accounting configuration", operationId = "retrieveAggregatorAccountingConfiguration", description = "Retrieves one normalized aggregator-to-GL mapping for the current tenant.")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = AggregatorAccountingConfigurationData.class)))
    public AggregatorAccountingConfigurationData retrieve(
            @PathParam("aggregatorCode") @Parameter(description = "Aggregator identifier; trimmed and uppercased before lookup") String aggregatorCode) {
        context.authenticatedUser().validateHasReadPermission(AggregatorAccountingConfigurationApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return service.retrieve(aggregatorCode);
    }

    @PUT
    @Path("{aggregatorCode}")
    @Operation(summary = "Create or replace an aggregator accounting configuration", operationId = "upsertAggregatorAccountingConfiguration", description = "Atomically replaces the complete GL mapping and active status for an aggregator.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AggregatorAccountingConfigurationRequest.class), examples = {
            @ExampleObject(name = "WITH_CONVENIENCE_FEE", summary = "Aggregator with its own convenience fee account", value = """
                    {
                      "aggregatorPayableGlAccountId": 101,
                      "commissionIncomeGlAccountId": 102,
                      "convenienceFeeIncomeGlAccountId": 103,
                      "active": true
                    }
                    """),
            @ExampleObject(name = "WITHOUT_CONVENIENCE_FEE", summary = "Aggregator that reuses the commission account for convenience fee", value = """
                    {
                      "aggregatorPayableGlAccountId": 101,
                      "commissionIncomeGlAccountId": 102,
                      "active": true
                    }
                    """) }))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = CommandProcessingResult.class)))
    public CommandProcessingResult upsert(
            @PathParam("aggregatorCode") @Parameter(description = "Aggregator identifier; trimmed and uppercased before storage") String aggregatorCode,
            @Parameter(hidden = true) AggregatorAccountingConfigurationRequest request) {
        CommandWrapper command = new CommandWrapperBuilder().upsertAggregatorAccountingConfiguration(aggregatorCode)
                .withJson(requestSerializer.serialize(request)).build();
        return commandSourceService.logCommandSource(command);
    }
}
