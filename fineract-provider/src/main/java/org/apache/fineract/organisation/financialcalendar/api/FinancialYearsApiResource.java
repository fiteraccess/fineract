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
package org.apache.fineract.organisation.financialcalendar.api;

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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.financialcalendar.data.FinancialYearData;
import org.apache.fineract.organisation.financialcalendar.data.FinancialYearRequest;
import org.apache.fineract.organisation.financialcalendar.service.FinancialCalendarService;
import org.springframework.stereotype.Component;

@Path("/v1/financialyears")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Component
@Tag(name = "Financial Years", description = "Financial years and their period codes (M01..M12). EOD derives a business date's period from"
        + " them, refuses to roll into a date no period covers, and on the roll into a new year requires its twelve periods and"
        + " confirmed holidays. The EOD run that leaves a year marks it closed (closedOn); a closed year is read-only.")
@RequiredArgsConstructor
public class FinancialYearsApiResource {

    private final PlatformSecurityContext context;
    private final FinancialCalendarService service;
    private final DefaultToApiJsonSerializer<FinancialYearRequest> requestSerializer;
    private final PortfolioCommandSourceWritePlatformService commandSourceService;

    @GET
    @Operation(summary = "List financial years", operationId = "retrieveAllFinancialYears")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = FinancialYearData.class))))
    public List<FinancialYearData> retrieveAll() {
        context.authenticatedUser().validateHasReadPermission(FinancialCalendarApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return service.retrieveAll();
    }

    @GET
    @Path("{financialCycle}")
    @Operation(summary = "Retrieve a financial year with its periods", operationId = "retrieveFinancialYear")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = FinancialYearData.class)))
    public FinancialYearData retrieve(@PathParam("financialCycle") @Parameter(description = "e.g. FY2026") String financialCycle) {
        context.authenticatedUser().validateHasReadPermission(FinancialCalendarApiConstants.RESOURCE_NAME_FOR_PERMISSION);
        return service.retrieve(financialCycle);
    }

    @PUT
    @Path("{financialCycle}")
    @Operation(summary = "Create or replace a financial year", operationId = "upsertFinancialYear", description = "Replaces the year and"
            + " its periods atomically. Periods must cover the year exactly, with no gap or overlap. A period with posted"
            + " transactions cannot be removed. A year the EOD chain has closed (closedOn set) cannot be changed. Dates are ISO"
            + " yyyy-MM-dd.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = FinancialYearRequest.class), examples = @ExampleObject(name = "FY2027", value = """
            {
              "description": "FISCAL YEAR 2027",
              "startDate": "2027-01-01",
              "endDate": "2027-12-31",
              "holidaysConfirmed": true,
              "periods": [
                { "periodCode": "M01", "startDate": "2027-01-01", "endDate": "2027-01-31" },
                { "periodCode": "M02", "startDate": "2027-02-01", "endDate": "2027-12-31" }
              ]
            }
            """)))
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = CommandProcessingResult.class)))
    public CommandProcessingResult upsert(@PathParam("financialCycle") @Parameter(description = "e.g. FY2027") String financialCycle,
            @Parameter(hidden = true) FinancialYearRequest request) {
        return commandSourceService.logCommandSource(
                new CommandWrapperBuilder().upsertFinancialYear(financialCycle).withJson(requestSerializer.serialize(request)).build());
    }

    @DELETE
    @Path("{financialCycle}")
    @Operation(summary = "Delete a financial year", operationId = "deleteFinancialYear", description = "Refused while any of its"
            + " periods has posted transactions (the error gives the count), and once the EOD chain has closed the year.")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = CommandProcessingResult.class)))
    public CommandProcessingResult delete(@PathParam("financialCycle") @Parameter(description = "e.g. FY2027") String financialCycle) {
        return commandSourceService
                .logCommandSource(new CommandWrapperBuilder().deleteFinancialYear(financialCycle).withJson("{}").build());
    }
}
