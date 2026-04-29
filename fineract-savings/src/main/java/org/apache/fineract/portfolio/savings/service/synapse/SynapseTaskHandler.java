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
package org.apache.fineract.portfolio.savings.service.synapse;

import org.apache.fineract.portfolio.savings.data.synapse.OutboxEntry;

/**
 * Strategy interface for dispatching outbox entries to Synapse.
 * <p>
 * Each implementation handles exactly one {@link #taskType()} (e.g. {@code INTEREST_POSTING}, {@code MONTHLY_CHARGE}).
 * The outbox dispatcher discovers all registered handlers via Spring's {@code List<SynapseTaskHandler>} injection.
 */
public interface SynapseTaskHandler {

    /**
     * The task type this handler is responsible for (e.g. {@code "INTEREST_POSTING"}).
     */
    String taskType();

    /**
     * Dispatch a single outbox entry to Synapse.
     * <p>
     * Implementations should deserialize the entry's payload, build the appropriate Synapse request, and call the
     * remote API. Errors should be propagated as {@link SynapsePostingException} so the circuit breaker can record
     * them.
     *
     * @param entry
     *            a claimed outbox row (status = DISPATCHED)
     */
    void dispatch(OutboxEntry entry);
}
