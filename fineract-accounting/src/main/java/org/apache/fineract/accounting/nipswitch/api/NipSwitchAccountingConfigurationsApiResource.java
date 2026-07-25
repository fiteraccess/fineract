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
package org.apache.fineract.accounting.nipswitch.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
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
import org.apache.fineract.accounting.nipswitch.data.NipSwitchAccountingConfigurationData;
import org.apache.fineract.accounting.nipswitch.data.NipSwitchAccountingConfigurationRequest;
import org.apache.fineract.accounting.nipswitch.service.NipSwitchAccountingConfigurationService;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/nip-switch-accounting-configurations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Component
@Tag(name = "NIP Switch Accounting Configurations", description = "Tenant-scoped GL routing for outbound NIP switches.")
@RequiredArgsConstructor
public class NipSwitchAccountingConfigurationsApiResource {

    private final PlatformSecurityContext context;
    private final NipSwitchAccountingConfigurationService service;
    private final DefaultToApiJsonSerializer<NipSwitchAccountingConfigurationRequest> requestSerializer;
    private final PortfolioCommandSourceWritePlatformService commandSourceService;

    @GET
    @Operation(summary = "List NIP switch accounting configurations", operationId = "retrieveAllNipSwitchAccountingConfigurations")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = NipSwitchAccountingConfigurationData.class))))
    public List<NipSwitchAccountingConfigurationData> retrieveAll() {
        context.authenticatedUser().validateHasReadPermission(NipSwitchAccountingConfigurationApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return service.retrieveAll();
    }

    @GET
    @Path("{switchId}")
    @Operation(summary = "Retrieve a NIP switch accounting configuration", operationId = "retrieveNipSwitchAccountingConfiguration")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = NipSwitchAccountingConfigurationData.class)))
    public NipSwitchAccountingConfigurationData retrieve(
            @PathParam("switchId") @Parameter(description = "NIP switch identifier") String switchId) {
        context.authenticatedUser().validateHasReadPermission(NipSwitchAccountingConfigurationApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return service.retrieve(switchId);
    }

    @PUT
    @Path("{switchId}")
    @Operation(summary = "Create or replace a NIP switch accounting configuration", operationId = "upsertNipSwitchAccountingConfiguration")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = NipSwitchAccountingConfigurationRequest.class)))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = CommandProcessingResult.class)))
    public CommandProcessingResult upsert(@PathParam("switchId") @Parameter(description = "NIP switch identifier") String switchId,
            @Parameter(hidden = true) NipSwitchAccountingConfigurationRequest request) {
        CommandWrapper command = new CommandWrapperBuilder().upsertNipSwitchAccountingConfiguration(switchId)
                .withJson(requestSerializer.serialize(request)).build();
        return commandSourceService.logCommandSource(command);
    }
}
