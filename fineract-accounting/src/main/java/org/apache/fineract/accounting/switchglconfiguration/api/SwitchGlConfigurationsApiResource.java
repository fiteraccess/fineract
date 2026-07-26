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
package org.apache.fineract.accounting.switchglconfiguration.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.switchglconfiguration.data.SwitchGlConfigurationData;
import org.apache.fineract.accounting.switchglconfiguration.data.request.SwitchGlConfigurationRequest;
import org.apache.fineract.accounting.switchglconfiguration.service.SwitchGlConfigurationReadPlatformService;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/switchglconfigurations")
@Component
@Tag(name = "Switch GL Configuration", description = """
        AB-416: maps a (switch, direction) pair - e.g. NIBSS outbound, NIBSS inbound - to the GL accounts a NIP \
        transfer through that switch should post against: a required Principal account (Payable for outbound, \
        Receivable for inbound), and, outbound only, a Switch-Fee account and a Bank-Commission account (inbound \
        NIP credits carry no fee).

        Field Descriptions
        switchCode
        Free-text identifier for the switch (e.g. NIBSS, HYDROGEN) - not a fixed enum, so new switches need no code change
        direction
        1 = OUTBOUND, 2 = INBOUND
        principalGlAccountId
        Always required - the Payable (outbound) or Receivable (inbound) GL account
        switchFeeGlAccountId / bankCommissionGlAccountId
        Required for OUTBOUND, must be omitted for INBOUND
        """)
@RequiredArgsConstructor
public class SwitchGlConfigurationsApiResource {

    private final PlatformSecurityContext context;
    private final SwitchGlConfigurationReadPlatformService switchGlConfigurationReadPlatformService;
    private final DefaultToApiJsonSerializer<SwitchGlConfigurationData> apiJsonSerializerService;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

    @GET
    @Path("template")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public SwitchGlConfigurationData retrieveTemplate() {
        this.context.authenticatedUser().validateHasReadPermission(SwitchGlConfigurationsApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return this.switchGlConfigurationReadPlatformService.retrieveTemplate();
    }

    @GET
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "List Switch GL Configurations", description = """
            Example Requests:
            switchglconfigurations""")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.GetSwitchGlConfigurationsResponse.class))))
    public List<SwitchGlConfigurationData> retrieveAll() {
        this.context.authenticatedUser().validateHasReadPermission(SwitchGlConfigurationsApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return this.switchGlConfigurationReadPlatformService.retrieveAll();
    }

    @GET
    @Path("{id}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve a Switch GL Configuration", description = """
            Example Requests:
            switchglconfigurations/1""")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.GetSwitchGlConfigurationsResponse.class)))
    public SwitchGlConfigurationData retrieveOne(@PathParam("id") @Parameter(description = "id") final Long id,
            @Context final UriInfo uriInfo) {
        this.context.authenticatedUser().validateHasReadPermission(SwitchGlConfigurationsApiConstants.RESOURCE_NAME_FOR_PERMISSION);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        SwitchGlConfigurationData switchGlConfigurationData = this.switchGlConfigurationReadPlatformService.retrieveOne(id);
        if (settings.isTemplate()) {
            switchGlConfigurationData = this.switchGlConfigurationReadPlatformService.addTemplateDetails(switchGlConfigurationData);
        }
        return switchGlConfigurationData;
    }

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Create a new Switch GL Configuration", description = """
            Mandatory Fields
            switchCode, direction, principalGlAccountId
            (switchFeeGlAccountId and bankCommissionGlAccountId are mandatory when direction is OUTBOUND, and must be omitted when direction is INBOUND)""")
    @RequestBody(content = @Content(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.PostSwitchGlConfigurationsRequest.class)))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.PostSwitchGlConfigurationsResponse.class)))
    public CommandProcessingResult create(@Parameter(hidden = true) final SwitchGlConfigurationRequest switchGlConfigurationRequest) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().createSwitchGlConfiguration()
                .withJson(this.apiJsonSerializerService.serialize(switchGlConfigurationRequest)).build();

        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    @PUT
    @Path("{id}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Update a Switch GL Configuration")
    @RequestBody(content = @Content(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.PostSwitchGlConfigurationsRequest.class)))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.PutSwitchGlConfigurationsResponse.class)))
    public CommandProcessingResult update(@PathParam("id") @Parameter(description = "id") final Long id,
            @Parameter(hidden = true) final SwitchGlConfigurationRequest switchGlConfigurationRequest) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().updateSwitchGlConfiguration(id)
                .withJson(this.apiJsonSerializerService.serialize(switchGlConfigurationRequest)).build();

        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    @DELETE
    @Path("{id}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Delete a Switch GL Configuration")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = SwitchGlConfigurationsApiResourceSwagger.DeleteSwitchGlConfigurationsResponse.class)))
    public CommandProcessingResult delete(@PathParam("id") @Parameter(description = "id") final Long id) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().deleteSwitchGlConfiguration(id).build();
        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }
}
