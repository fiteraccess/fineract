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
package org.apache.fineract.portfolio.savings.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;

/**
 * Event published when a savings transaction needs journal entries posted. Carries the accounting bridge data and the
 * {@link FineractContext} captured at publish-time so the async listener can restore thread-local context on a worker
 * thread.
 */
@Getter
@RequiredArgsConstructor
public class SavingsJournalEntryPostingEvent {

    private final SavingsAccountingBridgeDTO accountingBridgeData;
    private final FineractContext fineractContext;
}
