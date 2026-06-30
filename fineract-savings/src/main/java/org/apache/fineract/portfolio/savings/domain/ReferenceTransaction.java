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
package org.apache.fineract.portfolio.savings.domain;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;

/**
 * AB-265: side-effect transaction asserted by an upstream system (Synapse) to be created alongside a primary savings
 * transaction (deposit, withdrawal, transfer) inside the same DB transaction. Used to carry the EMT Levy row whose
 * threshold + direction + intra-client decision was made in Synapse.
 *
 * <p>
 * The receiver does not re-evaluate the rule — it just records what the caller asserts, linked to the parent via the
 * parent's {@code ref_no}. Forward-compatible with future siblings (VAT, etc.).
 */
public record ReferenceTransaction(SavingsAccountTransactionType type, BigDecimal amount) {

    public ReferenceTransaction {
        if (type == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.type.required",
                    "Reference transaction type is required");
        }
        if (amount == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.amount.required",
                    "Reference transaction amount is required");
        }
        if (amount.signum() <= 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.amount.not.positive",
                    "Reference transaction amount must be positive: " + amount, amount);
        }
    }
}
