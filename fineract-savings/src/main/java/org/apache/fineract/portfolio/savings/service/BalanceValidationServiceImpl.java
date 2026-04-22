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
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.springframework.stereotype.Service;

/**
 * O(1) implementation of balance validation for savings accounts.
 *
 * Instead of iterating over all historical transactions to recompute the running balance (O(n)), this service uses the
 * pre-computed account balance stored in {@link org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary} to
 * validate withdrawal constraints in constant time.
 *
 * The validation logic mirrors the rules in
 * {@link SavingsAccount#validateAccountBalanceDoesNotBecomeNegativeMinimal(BigDecimal, boolean)} but is extracted into
 * a standalone service for use by the domain service layer.
 */
@Service
public class BalanceValidationServiceImpl implements BalanceValidationService {

    @Override
    public void validateBalance(final SavingsAccount account, final BigDecimal transactionAmount,
            final boolean isExceptionForBalanceCheck) {

        final MonetaryCurrency currency = account.getCurrency();
        final Money accountBalance = account.getSummary().getAccountBalance(currency);
        final Money withdrawalAmount = Money.of(currency, transactionAmount);

        // Compute the balance after the proposed withdrawal, accounting for guarantor holds
        final Money onHoldFundsMoney = Money.of(currency, account.getOnHoldFunds());
        final Money balanceAfterWithdrawal = accountBalance.minus(withdrawalAmount).minus(onHoldFundsMoney);

        // Compute the effective minimum required balance (includes overdraft allowance)
        final Money minRequiredBalance = computeMinRequiredBalance(account, currency);

        // For non-overdraft accounts, validate immediately (unless exception flag is set)
        if (!isExceptionForBalanceCheck && !account.isAllowOverdraft()) {
            if (balanceAfterWithdrawal.minus(minRequiredBalance).isLessThanZero()) {
                throw new InsufficientAccountBalanceException("transactionAmount", account.getAccountBalance(), null, transactionAmount);
            }
        }

        // For overdraft accounts, check against overdraft limit
        if (account.isAllowOverdraft()) {
            if (balanceAfterWithdrawal.minus(minRequiredBalance).isLessThanZero()) {
                throw new InsufficientAccountBalanceException("transactionAmount", account.getAccountBalance(), null, transactionAmount);
            }
        }

        // Check savings hold amount (lien/pledge holds)
        final BigDecimal savingsHoldAmount = account.getSavingsHoldAmount();
        if (savingsHoldAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (account.isEnforceMinRequiredBalance()) {
                final Money effectiveMinBalance = computeMinRequiredBalance(account, currency);
                if (balanceAfterWithdrawal.minus(effectiveMinBalance.plus(savingsHoldAmount)).isLessThanZero()) {
                    throw new InsufficientAccountBalanceException("transactionAmount", account.getAccountBalance(), null,
                            transactionAmount);
                }
            } else {
                if (balanceAfterWithdrawal.minus(savingsHoldAmount).isLessThanZero()) {
                    throw new InsufficientAccountBalanceException("transactionAmount", account.getAccountBalance(), null,
                            transactionAmount);
                }
            }
        }
    }

    /**
     * Computes the effective minimum required balance, accounting for both enforced minimum balance and overdraft
     * allowance. This mirrors the logic in {@link SavingsAccount#minRequiredBalanceDerived(MonetaryCurrency)}.
     */
    private Money computeMinRequiredBalance(final SavingsAccount account, final MonetaryCurrency currency) {
        Money minReqBalance = Money.zero(currency);
        if (account.isEnforceMinRequiredBalance()) {
            minReqBalance = minReqBalance.plus(account.getMinRequiredBalance());
        }
        if (account.isAllowOverdraft()) {
            minReqBalance = minReqBalance.minus(account.getOverdraftLimit());
        }
        return minReqBalance;
    }
}
