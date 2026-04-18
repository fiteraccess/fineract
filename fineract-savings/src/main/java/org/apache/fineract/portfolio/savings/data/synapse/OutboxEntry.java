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
package org.apache.fineract.portfolio.savings.data.synapse;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a single row in the {@code synapse_outbox} table.
 * <p>
 * Task-type agnostic — the {@code payload} field is opaque JSON whose structure
 * is defined by the corresponding {@code SynapseTaskHandler}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEntry {

    private Long id;
    private String traceId;
    private String batchId;
    private String taskType;
    private Long accountId;
    private Long officeId;
    private String payload;
    @Builder.Default
    private String status = "PENDING";
    @Builder.Default
    private int attempts = 0;
    @Builder.Default
    private int maxAttempts = 1000;
    private String errorDetail;
    private Instant createdAt;
    private Instant dispatchedAt;
    private Instant completedAt;
    private Instant nextAttemptAt;
}
