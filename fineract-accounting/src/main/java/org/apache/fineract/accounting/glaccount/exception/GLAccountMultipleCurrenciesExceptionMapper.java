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
package org.apache.fineract.accounting.glaccount.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Maps {@link GLAccountMultipleCurrenciesException} to HTTP 409. No existing Fineract exception family fits: a
 * validation failure is 400, a permission failure is 403 (including {@code GeneralPlatformDomainRuleException}), and a
 * missing resource is 404. This is none of those — the resource exists and the caller is authorised, but the request as
 * given is ambiguous, which is precisely what 409 Conflict is for.
 */
@Provider
@Component
@Scope("singleton")
public class GLAccountMultipleCurrenciesExceptionMapper implements ExceptionMapper<GLAccountMultipleCurrenciesException> {

    @Override
    public Response toResponse(final GLAccountMultipleCurrenciesException exception) {
        final ApiGlobalErrorResponse response = ApiGlobalErrorResponse.create(Status.CONFLICT.getStatusCode(),
                exception.getGlobalisationMessageCode(), exception.getDefaultUserMessage(), exception.getDefaultUserMessage());
        return Response.status(Status.CONFLICT).entity(response).type(MediaType.APPLICATION_JSON).build();
    }
}
