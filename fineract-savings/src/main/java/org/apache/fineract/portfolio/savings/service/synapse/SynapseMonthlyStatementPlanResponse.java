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
 * What Synapse reports after enqueueing a monthly statement run (AB-358, R-D-24).
 *
 * <p>
 * {@code enqueued} counts rows created, not statements delivered — Synapse's own worker renders and emails them
 * afterwards. A repeat run for a month already enqueued reports {@code 0} rather than failing, because the unique index
 * on the document table rejects the duplicates.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseMonthlyStatementPlanResponse {

    private Integer enqueued;
    private String periodFrom;
    private String periodTo;
}
