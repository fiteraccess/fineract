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
package org.apache.fineract.accounting.aggregatoraccounting.data;

import io.swagger.v3.oas.annotations.media.Schema;

public record AggregatorAccountingConfigurationData(
        @Schema(description = "Normalized aggregator identifier.", example = "CORALPAY") String aggregatorCode,
        @Schema(description = "GL credited for the aggregator payable principal leg.", example = "101") Long aggregatorPayableGlAccountId,
        @Schema(description = "GL credited for the bank's commission income leg.", example = "102") Long commissionIncomeGlAccountId,
        @Schema(description = "GL credited for the convenience fee income leg.", example = "103") Long convenienceFeeIncomeGlAccountId,
        @Schema(description = "Whether this mapping may be used for new bills/airtime postings.", example = "true") boolean active) {
}
