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
package org.apache.fineract.portfolio.savings.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed savings accounting bridge. Lives in fineract-accounting (same package as before) because the journal entry
 * services that consume it were moved there and cannot see fineract-savings.
 */
@Data
@NoArgsConstructor
public class SavingsAccountingBridgeDTO {

    private Long savingsId;
    private Long savingsProductId;
    private Long officeId;
    private String currencyCode;
    private boolean cashBasedAccountingEnabled;
    private boolean accrualBasedAccountingEnabled;
    private boolean accountTransfer;
    private List<SavingsAccountingBridgeTransactionDTO> newSavingsTransactions = new ArrayList<>();
    /**
     * Ids of the new transactions that belong to an account transfer even though the bridge as a whole is not flagged
     * as one, e.g. transfer legs re-created during an overdraft recalculation (FINERACT-754).
     */
    private Set<Long> accountTransferTransactionIds = new HashSet<>();

    public SavingsAccountingBridgeDTO(final Long savingsId, final Long savingsProductId, final Long officeId, final String currencyCode,
            final boolean cashBasedAccountingEnabled, final boolean accrualBasedAccountingEnabled, final boolean accountTransfer,
            final List<SavingsAccountingBridgeTransactionDTO> newSavingsTransactions) {
        this.savingsId = savingsId;
        this.savingsProductId = savingsProductId;
        this.officeId = officeId;
        this.currencyCode = currencyCode;
        this.cashBasedAccountingEnabled = cashBasedAccountingEnabled;
        this.accrualBasedAccountingEnabled = accrualBasedAccountingEnabled;
        this.accountTransfer = accountTransfer;
        this.newSavingsTransactions = newSavingsTransactions;
    }
}
