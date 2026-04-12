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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
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
}
