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
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDailyBalance;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDailyBalanceRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.domain.interest.SavingsAccountTransactionDetailsForPostingPeriod;
import org.springframework.stereotype.Service;

/**
 * Calculates interest from daily balance snapshots instead of replaying all transactions. Provides
 * O(days_with_snapshots) interest calculation instead of O(total_transactions).
 */
@Service
@RequiredArgsConstructor
public class SnapshotInterestCalculationServiceImpl implements SnapshotInterestCalculationService {

    private final SavingsAccountDailyBalanceRepository dailyBalanceRepository;
    private final SavingsHelper savingsHelper;

    @Override
    public List<PostingPeriod> calculateInterestFromSnapshots(final SavingsAccount account, final MathContext mc, final LocalDate upToDate,
            final boolean isInterestTransfer, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final Integer financialYearBeginningMonth) {

        final MonetaryCurrency currency = account.getCurrency();
        final List<PostingPeriod> allPostingPeriods = new ArrayList<>();

        final SavingsPostingInterestPeriodType postingPeriodType = SavingsPostingInterestPeriodType
                .fromInt(account.getInterestPostingPeriodType());
        final SavingsCompoundingInterestPeriodType compoundingPeriodType = SavingsCompoundingInterestPeriodType
                .fromInt(account.getInterestCompoundingPeriodType());
        final SavingsInterestCalculationDaysInYearType daysInYearType = SavingsInterestCalculationDaysInYearType
                .fromInt(account.getInterestCalculationDaysInYearType());
        final SavingsInterestCalculationType interestCalculationType = SavingsInterestCalculationType
                .fromInt(account.getInterestCalculationType());

        final BigDecimal interestRateAsFraction = account.getNominalAnnualInterestRate().divide(BigDecimal.valueOf(100L), mc);
        final BigDecimal overdraftInterestRateAsFraction = account.getNominalAnnualInterestRateOverdraft() != null
                ? account.getNominalAnnualInterestRateOverdraft().divide(BigDecimal.valueOf(100L), mc)
                : BigDecimal.ZERO;

        final Money minBalanceForInterestCalculation = Money.of(currency, account.minBalanceForInterestCalculation());
        final Money minOverdraftForInterestCalculation = Money.of(currency,
                account.getMinOverdraftForInterestCalculation() != null ? account.getMinOverdraftForInterestCalculation()
                        : BigDecimal.ZERO);

        final LocalDate startDate = account.getStartInterestCalculationDate();
        final List<LocalDateInterval> postingPeriodIntervals = savingsHelper.determineInterestPostingPeriods(startDate, upToDate,
                postingPeriodType, financialYearBeginningMonth, Collections.emptyList());

        // Load all snapshots for the full interest calculation period
        final List<SavingsAccountDailyBalance> snapshots = dailyBalanceRepository.findByAccountAndDateRange(account.getId(), startDate,
                upToDate);

        Money periodStartingBalance = Money.zero(currency);

        for (final LocalDateInterval periodInterval : postingPeriodIntervals) {
            // Build synthetic transaction details from snapshots that fall within this period
            final List<SavingsAccountTransactionDetailsForPostingPeriod> txnDetails = buildTransactionDetailsFromSnapshots(snapshots,
                    periodInterval, currency, account.isAllowOverdraft());

            final Collection<Long> interestPostTransactions = Collections.emptyList();
            final boolean isUserPosting = false;

            final PostingPeriod postingPeriod = PostingPeriod.createFrom(periodInterval, periodStartingBalance, txnDetails, currency,
                    compoundingPeriodType, interestCalculationType, interestRateAsFraction, daysInYearType.getValue(), upToDate,
                    interestPostTransactions, isInterestTransfer, minBalanceForInterestCalculation,
                    isSavingsInterestPostingAtCurrentPeriodEnd, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation,
                    isUserPosting, financialYearBeginningMonth);

            periodStartingBalance = postingPeriod.closingBalance();
            allPostingPeriods.add(postingPeriod);
        }

        // isTransferInterestToOtherAccount() is protected on SavingsAccount (defaults to false).
        // Subclasses override it, but for standard savings accounts it's always false.
        final boolean immediateWithdrawalOfInterest = false;
        savingsHelper.calculateInterestForAllPostingPeriods(currency, allPostingPeriods, account.getLockedInUntilDate(),
                immediateWithdrawalOfInterest);

        return allPostingPeriods;
    }

    /**
     * Converts daily balance snapshots into SavingsAccountTransactionDetailsForPostingPeriod objects that PostingPeriod
     * can consume. Each snapshot becomes a synthetic "transaction" with balance information.
     */
    private List<SavingsAccountTransactionDetailsForPostingPeriod> buildTransactionDetailsFromSnapshots(
            final List<SavingsAccountDailyBalance> allSnapshots, final LocalDateInterval periodInterval, final MonetaryCurrency currency,
            final boolean allowOverdraft) {

        final List<SavingsAccountTransactionDetailsForPostingPeriod> details = new ArrayList<>();

        for (final SavingsAccountDailyBalance snapshot : allSnapshots) {
            if (!periodInterval.contains(snapshot.getBalanceDate())) {
                continue;
            }
            // Calculate days until next snapshot or end of period
            final LocalDate endDate = periodInterval.endDate();
            final int daysOfBalance = (int) ChronoUnit.DAYS.between(snapshot.getBalanceDate(), endDate) + 1;

            details.add(new SavingsAccountTransactionDetailsForPostingPeriod(null, // id - not a real transaction
                    snapshot.getBalanceDate(), endDate, snapshot.getEndOfDayBalance(), // runningBalance
                    BigDecimal.ZERO, // amount - not relevant for balance-based calculation
                    currency, daysOfBalance, false, // isDeposit
                    false, // isWithdrawal
                    allowOverdraft, false, // isChargeTransactionAndNotReversed
                    false // isDividendPayoutAndNotReversed
            ));
        }

        return details;
    }
}
