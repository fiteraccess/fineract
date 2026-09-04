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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * What Synapse reports after accepting a monthly statement run (AB-358, R-D-24).
 *
 * <p>
 * {@code started} says the run was accepted — nothing more. Synapse enqueues the accounts on a background planner and
 * then renders and emails them on its own workers, so neither the enqueued count nor the delivered count is known when
 * this response is written. Both are reported by Synapse: the count in its log and the
 * {@code fin.proxy.access.statement.monthly.enqueued} metric, delivery by {@code m_statement_document.status}.
 *
 * <p>
 * {@code false} means Synapse was already planning this month and ignored the duplicate request. That is a normal
 * outcome for a second press of Run Selected Jobs, not an error.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseMonthlyStatementPlanResponse {

    private Boolean started;
    private String periodFrom;
    private String periodTo;
}
