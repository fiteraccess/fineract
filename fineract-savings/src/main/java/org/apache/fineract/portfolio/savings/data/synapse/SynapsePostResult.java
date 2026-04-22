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

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result returned by {@code SynapseInterestPostingService.postInterestBatch()}. Contains cursor updates for accounts
 * whose Synapse instructions all succeeded (plus zero-interest accounts that had no instructions to send).
 */
@Getter
@AllArgsConstructor
public class SynapsePostResult {

    /** Cursor updates to persist — only includes accounts that fully succeeded. */
    private final List<AccountCursorUpdate> cursorUpdates;

    /** Number of accounts whose instructions were all accepted by Synapse. */
    private final int accepted;

    /** Number of accounts that had at least one rejected instruction. */
    private final int failed;
}
