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
package org.apache.fineract.portfolio.savings.service;

import java.math.BigDecimal;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;

/**
 * O(1) balance validation service for savings accounts.
 *
 * Validates that a withdrawal or debit transaction will not cause the account balance to violate constraints (minimum
 * required balance, overdraft limit, hold amounts) without iterating over historical transactions.
 *
 * Uses the pre-computed account balance from {@link org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary}
 * along with account-level configuration (overdraft, min balance, holds) to perform constant-time validation.
 */
public interface BalanceValidationService {

    /**
     * Validates that the given withdrawal amount will not cause the account balance to go below the allowed minimum,
     * considering:
     * <ul>
     * <li>Current account balance (from summary)</li>
     * <li>Minimum required balance (if enforced)</li>
     * <li>Overdraft limit (if allowed)</li>
     * <li>On-hold funds (guarantor holds)</li>
     * <li>Savings on-hold amount (lien/pledge holds)</li>
     * </ul>
     *
     * @param account
     *            the savings account to validate
     * @param transactionAmount
     *            the withdrawal amount to validate
     * @param isExceptionForBalanceCheck
     *            if true, skip balance check for non-overdraft accounts
     * @throws org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException
     *             if the withdrawal would violate balance constraints
     */
    void validateBalance(SavingsAccount account, BigDecimal transactionAmount, boolean isExceptionForBalanceCheck);
}
