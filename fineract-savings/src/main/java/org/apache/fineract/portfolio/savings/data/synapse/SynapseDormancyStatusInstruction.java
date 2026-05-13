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

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class SynapseDormancyStatusInstruction {

    private final String traceId;
    private final Long savingsAccountId;
    private final Long clientId;
    private final Long officeId;
    private final SavingsAccountSubStatusEnum previousSubStatus;
    private final SavingsAccountSubStatusEnum targetSubStatus;
    private final LocalDate effectiveDate;
    private final String transitionReason;
    private final String currencyCode;
}
