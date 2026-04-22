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
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.springframework.stereotype.Component;

/**
 * A wrapper for dealing with side-effect free functionality related to a {@link SavingsAccount}'s
 * {@link SavingsAccountTransaction}'s.
 */
@Component
public final class SavingsAccountTransactionSummaryWrapper {

    public BigDecimal calculateTotalDeposits(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if ((transaction.isDepositAndNotReversed() || transaction.isDividendPayoutAndNotReversed())
                    && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalWithdrawals(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isWithdrawal() && transaction.isNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalInterestPosted(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isInterestPostingAndNotReversed() && transaction.isNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalInterestPosted(final MonetaryCurrency currency, final BigDecimal currentInterestPosted,
            final List<SavingsAccountTransaction> savingsAccountTransactions) {
        Money total = Money.of(currency, currentInterestPosted);
        for (final SavingsAccountTransaction transaction : savingsAccountTransactions) {
            if (transaction.isInterestPostingAndNotReversed() && transaction.isNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalWithdrawalFees(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isWithdrawalFeeAndNotReversed() && transaction.isNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalAnnualFees(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isAnnualFeeAndNotReversed() && transaction.isNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalFeesCharge(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isFeeChargeAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalFeesChargeWaived(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isWaiveFeeChargeAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalPenaltyCharge(final MonetaryCurrency currency, final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isPenaltyChargeAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalPenaltyChargeWaived(final MonetaryCurrency currency,
            final List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isWaivePenaltyChargeAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalOverdraftInterest(MonetaryCurrency currency, BigDecimal overdraftPosted,
            List<SavingsAccountTransaction> transactions) {
        Money total = Money.of(currency, overdraftPosted);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isOverdraftInterestAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalOverdraftInterest(MonetaryCurrency currency, List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isOverdraftInterestAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    public BigDecimal calculateTotalWithholdTaxWithdrawal(MonetaryCurrency currency, List<SavingsAccountTransaction> transactions) {
        Money total = Money.zero(currency);
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isWithHoldTaxAndNotReversed() && !transaction.isReversalTransaction()) {
                total = total.plus(transaction.getAmount(currency));
            }
        }
        return total.getAmountDefaultedToNullIfZero();
    }

    /**
     * Computes the incremental summary delta for a single transaction in O(1) time. Returns a
     * {@link SavingsAccountSummaryDelta} containing the amounts to add to each summary field. This avoids iterating
     * over all historical transactions.
     *
     * @param currency
     *            the account currency
     * @param transaction
     *            the single transaction to process
     * @return the delta to apply to the summary, or null if the transaction should be skipped (e.g. reversal
     *         transactions)
     */
    public SavingsAccountSummaryDelta computeIncrementalDelta(final MonetaryCurrency currency,
            final SavingsAccountTransaction transaction) {
        if (transaction.isReversalTransaction()) {
            return null;
        }

        final SavingsAccountSummaryDelta delta = new SavingsAccountSummaryDelta();
        final BigDecimal amount = transaction.getAmount();
        final SavingsAccountTransactionType txnType = transaction.getTransactionType();

        switch (txnType) {
            case DEPOSIT:
                if (transaction.isDepositAndNotReversed()) {
                    delta.setTotalDeposits(amount);
                    delta.setAccountBalance(amount);
                }
            break;
            case DIVIDEND_PAYOUT:
                if (transaction.isDividendPayoutAndNotReversed()) {
                    delta.setTotalDeposits(amount);
                    delta.setAccountBalance(amount);
                }
            break;
            case WITHDRAWAL:
                if (transaction.isWithdrawal() && transaction.isNotReversed()) {
                    delta.setTotalWithdrawals(amount);
                    delta.setAccountBalance(amount.negate());
                }
            break;
            case INTEREST_POSTING:
                if (transaction.isInterestPostingAndNotReversed() && transaction.isNotReversed()) {
                    delta.setTotalInterestPosted(amount);
                    delta.setAccountBalance(amount);
                }
            break;
            case WITHDRAWAL_FEE:
                if (transaction.isWithdrawalFeeAndNotReversed() && transaction.isNotReversed()) {
                    delta.setTotalWithdrawalFees(amount);
                    delta.setTotalFeeCharge(amount);
                    delta.setAccountBalance(amount.negate());
                }
            break;
            case ANNUAL_FEE:
                if (transaction.isAnnualFeeAndNotReversed() && transaction.isNotReversed()) {
                    delta.setTotalAnnualFees(amount);
                    delta.setTotalFeeCharge(amount);
                    delta.setAccountBalance(amount.negate());
                }
            break;
            case WAIVE_CHARGES:
                if (transaction.isWaiveFeeChargeAndNotReversed()) {
                    delta.setTotalFeeChargesWaived(amount);
                } else if (transaction.isWaivePenaltyChargeAndNotReversed()) {
                    delta.setTotalPenaltyChargesWaived(amount);
                }
            break;
            case PAY_CHARGE:
                if (transaction.isFeeChargeAndNotReversed()) {
                    delta.setTotalFeeCharge(amount);
                    delta.setAccountBalance(amount.negate());
                } else if (transaction.isPenaltyChargeAndNotReversed()) {
                    delta.setTotalPenaltyCharge(amount);
                    delta.setAccountBalance(amount.negate());
                }
            break;
            case OVERDRAFT_INTEREST:
                if (transaction.isOverdraftInterestAndNotReversed()) {
                    delta.setTotalOverdraftInterestDerived(amount);
                    delta.setAccountBalance(amount.negate());
                }
            break;
            case WITHHOLD_TAX:
                if (transaction.isWithHoldTaxAndNotReversed()) {
                    delta.setTotalWithholdTax(amount);
                    delta.setAccountBalance(amount.negate());
                }
            break;
            default:
            break;
        }

        return delta;
    }

}
