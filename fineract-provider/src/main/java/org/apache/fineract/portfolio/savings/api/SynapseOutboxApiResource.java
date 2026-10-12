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

import com.google.gson.JsonElement;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Path("/v1/synapse-outbox")
@Component
@ConditionalOnProperty(prefix = "fineract.synapse", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SynapseOutboxApiResource {

    private final PlatformSecurityContext context;
    private final SynapseOutboxRepository synapseOutboxRepository;
    private final FromJsonHelper fromJsonHelper;

    @POST
    @Path("{id}/retry")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retryDeadEntry(@PathParam("id") final Long id) {
        final boolean hasNotPermission = context.authenticatedUser().hasNotPermissionForAnyOf("ALL_FUNCTIONS");
        if (hasNotPermission) {
            throw new NoAuthorizationException("User has no authority to retry dead outbox entries");
        }
        int updated = synapseOutboxRepository.retryDeadEntry(id);
        return "{\"resourceId\": " + id + ", \"updated\": " + updated + "}";
    }

    /**
     * Cancels a DEAD entry Synapse will never accept, with the operator's reason. A cancelled entry no longer holds its
     * account's later entries. Answers {@code updated: 0} for an entry that is missing or not DEAD.
     */
    @POST
    @Path("{id}/cancel")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String cancelDeadEntry(@PathParam("id") final Long id, final String apiRequestBodyAsJson) {
        final String username = requireOutboxAuthority("cancel");
        final JsonElement body = fromJsonHelper.parse(apiRequestBodyAsJson);
        final String reason = fromJsonHelper.extractStringNamed("reason", body);
        final DataValidatorBuilder validator = new DataValidatorBuilder(new ArrayList<ApiParameterError>()).resource("synapseOutbox");
        validator.reset().parameter("reason").value(reason).notBlank().notExceedingLengthOf(500);
        validator.throwValidationErrors();
        int updated = synapseOutboxRepository.cancelDeadEntry(id, username, reason);
        return "{\"resourceId\": " + id + ", \"updated\": " + updated + "}";
    }

    /** Cancels every DEAD entry of a task type created before a date, with the operator's reason. */
    @POST
    @Path("cancel-dead")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String cancelDeadEntries(final String apiRequestBodyAsJson) {
        final String username = requireOutboxAuthority("cancel");
        final JsonElement body = fromJsonHelper.parse(apiRequestBodyAsJson);
        final String taskType = fromJsonHelper.extractStringNamed("taskType", body);
        final String reason = fromJsonHelper.extractStringNamed("reason", body);
        final String createdBefore = fromJsonHelper.extractStringNamed("createdBefore", body);
        final DataValidatorBuilder validator = new DataValidatorBuilder(new ArrayList<ApiParameterError>()).resource("synapseOutbox");
        validator.reset().parameter("taskType").value(taskType).notBlank();
        validator.reset().parameter("reason").value(reason).notBlank().notExceedingLengthOf(500);
        validator.reset().parameter("createdBefore").value(createdBefore).notBlank();
        validator.throwValidationErrors();
        final Instant before = LocalDate.parse(createdBefore).atStartOfDay(ZoneOffset.UTC).toInstant();
        int updated = synapseOutboxRepository.cancelDeadEntriesBefore(taskType, before, username, reason);
        return "{\"taskType\": \"" + taskType + "\", \"updated\": " + updated + "}";
    }

    private String requireOutboxAuthority(String action) {
        if (context.authenticatedUser().hasNotPermissionForAnyOf("ALL_FUNCTIONS")) {
            throw new NoAuthorizationException("User has no authority to " + action + " dead outbox entries");
        }
        return context.authenticatedUser().getUsername();
    }
}
