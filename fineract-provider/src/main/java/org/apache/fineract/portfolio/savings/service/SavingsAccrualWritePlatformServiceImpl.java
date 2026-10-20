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

import jakarta.persistence.OptimisticLockException;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.data.SavingsAccrualData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsAccrualWritePlatformServiceImpl implements SavingsAccrualWritePlatformService {

    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final SavingsAccountDomainService savingsAccountDomainService;
    private final PlatformTransactionManager transactionManager;
    private final SavingsInterestForfeitureRepository forfeitureRepository;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;

    /** A concurrent Synapse replay can bump an account's version mid-run; a fresh read of the account then succeeds. */
    private static final int MAX_ATTEMPTS_PER_ACCOUNT = 3;

    @Override
    public void addAccrualEntries(LocalDate tillDate) throws JobExecutionException {
        final List<SavingsAccrualData> savingsAccrualData = savingsAccountReadPlatformService.retrievePeriodicAccrualData(tillDate, null);
        final Integer financialYearBeginningMonth = configurationDomainService.retrieveFinancialYearBeginningMonth();
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final MathContext mc = MoneyHelper.getMathContext();
        final Map<Long, Map<LocalDate, Boolean>> forfeitures = loadForfeitures(savingsAccrualData, tillDate);

        // AB-401: one account per transaction, so a conflict on one account no longer rolls back every other account's
        // accrual.
        final TransactionTemplate perAccount = new TransactionTemplate(transactionManager);
        perAccount.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<Throwable> errors = new ArrayList<>();
        for (SavingsAccrualData savingsAccrual : savingsAccrualData) {
            try {
                if (savingsAccrual.getDepositType().isSavingsDeposit() && savingsAccrual.getIsAllowOverdraft()) {
                    if (!savingsAccrual.getIsTypeInterestReceivable()) {
                        continue;
                    }
                }
                accrueInOwnTransaction(perAccount, savingsAccrual, tillDate, financialYearBeginningMonth,
                        isSavingsInterestPostingAtCurrentPeriodEnd, mc, forfeitures.getOrDefault(savingsAccrual.getId(), Map.of()));
            } catch (Exception e) {
                log.error("Failed to add accrual transaction for savings {} : {}", savingsAccrual.getAccountNo(), e.getMessage());
                errors.add(e.getCause() != null ? e.getCause() : e);
            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
    }

    /**
     * AB-566: Regular Savings eligibility for every month the run can touch, in one query. The window opens a month
     * back so a month Synapse settles after its last end of day, but before it is posted, is still reconciled.
     */
    private Map<Long, Map<LocalDate, Boolean>> loadForfeitures(final List<SavingsAccrualData> savingsAccrualData,
            final LocalDate tillDate) {
        if (savingsAccrualData.isEmpty()) {
            return Map.of();
        }
        LocalDate from = tillDate.minusMonths(1);
        for (SavingsAccrualData savingsAccrual : savingsAccrualData) {
            if (savingsAccrual.getAccruedTill() != null && savingsAccrual.getAccruedTill().isBefore(from)) {
                from = savingsAccrual.getAccruedTill();
            }
        }
        return forfeitureRepository.findStatesBetween(from.withDayOfMonth(1), tillDate);
    }

    private void accrueInOwnTransaction(final TransactionTemplate perAccount, final SavingsAccrualData savingsAccrual,
            final LocalDate tillDate, final Integer financialYearBeginningMonth, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final MathContext mc, final Map<LocalDate, Boolean> forfeitureByMonth) {
        for (int attempt = 1;; attempt++) {
            try {
                perAccount.executeWithoutResult(status -> {
                    SavingsAccount savingsAccount = savingsAccountAssembler.assembleFrom(savingsAccrual.getId(), false);
                    LocalDate fromDate = savingsAccrual.getAccruedTill();
                    if (fromDate == null) {
                        fromDate = savingsAccount.getActivationDate();
                    }
                    log.debug("Processing savings account {} from date {} till date {}", savingsAccrual.getAccountNo(), fromDate, tillDate);
                    addAccrualTransactions(savingsAccount, fromDate, tillDate, financialYearBeginningMonth,
                            isSavingsInterestPostingAtCurrentPeriodEnd, mc, null, forfeitureByMonth);
                });
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS_PER_ACCOUNT || !isOptimisticLock(e)) {
                    throw e;
                }
                log.warn("Savings {} changed during accrual (attempt {}), retrying", savingsAccrual.getAccountNo(), attempt);
            }
        }
    }

    private static boolean isOptimisticLock(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OptimisticLockingFailureException || t instanceof OptimisticLockException
                    || t instanceof org.eclipse.persistence.exceptions.OptimisticLockException) {
                return true;
            }
        }
        return false;
    }

    /**
     * AB-401: books each day the interest the posting engine has earned so far in that day's posting period, less what
     * is already accrued in it, so a period's accruals always add up to what interest posting credits for it.
     */
    private void addAccrualTransactions(SavingsAccount savingsAccount, final LocalDate fromDate, final LocalDate tillDate,
            final Integer financialYearBeginningMonth, final boolean isSavingsInterestPostingAtCurrentPeriodEnd, final MathContext mc,
            final Function<LocalDate, String> refNoProvider, final Map<LocalDate, Boolean> forfeitureByMonth) {
        final Set<Long> existingTransactionIds = new HashSet<>(savingsAccount.findExistingTransactionIds());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(savingsAccount.findExistingReversedTransactionIds());

        if (savingsAccount.savingsProduct().getProductCategory() == org.apache.fineract.portfolio.savings.SavingsProductCategory.GOAL) {
            org.apache.fineract.portfolio.savings.domain.GoalSavingsAccrual.accrue(savingsAccount, tillDate);
            savingsAccountRepository.saveAndFlush(savingsAccount);
            savingsAccountDomainService.postJournalEntries(savingsAccount, existingTransactionIds, existingReversedTransactionIds, false);
            return;
        }
        final List<SavingsAccountTransaction> forfeitedAccruals = new ArrayList<>();
        final LocalDate startDate = applyForfeitures(savingsAccount, fromDate, forfeitureByMonth, forfeitedAccruals);
        LocalDate accruedTillDate = null;
        for (LocalDate day = startDate; !day.isAfter(tillDate) && !DateUtils.isDateInTheFuture(day); day = day.plusDays(1)) {
            accruedTillDate = day;
            if (isForfeited(forfeitureByMonth, day) || hasLiveAccrualOn(savingsAccount, day)) {
                continue;
            }
            final PostingPeriod period = periodContaining(
                    savingsAccount.projectInterestUsing(mc, day, isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth),
                    day);
            if (period == null) {
                continue;
            }
            final Money earned = period.getInterestEarned();
            Money amount = earned.abs().minus(accruedInPeriodBefore(savingsAccount, period.getPeriodInterval(), day));
            if (amount.isLessThanZero()) {
                // Earlier accruals overshoot the engine (booked on stale balances): re-base the period on it in one
                // entry.
                savingsAccount.reverseAccrualsFrom(period.getPeriodInterval().startDate(), false);
                amount = earned.abs();
            }
            if (amount.isZero()) {
                continue;
            }
            final String refNo = refNoProvider != null ? refNoProvider.apply(day) : null;
            final SavingsAccountTransaction accrual = SavingsAccountTransaction.accrual(savingsAccount, savingsAccount.office(), day,
                    amount, false, refNo);
            accrual.setRunningBalance(period.getClosingBalance());
            accrual.setOverdraftAmount(earned.isLessThanZero() ? amount.negated() : amount);
            savingsAccount.addTransaction(accrual);
        }

        if (accruedTillDate != null) {
            savingsAccount.setAccruedTillDate(accruedTillDate);
        }
        savingsAccountRepository.saveAndFlush(savingsAccount);
        savingsAccountDomainService.postJournalEntries(savingsAccount, existingTransactionIds, existingReversedTransactionIds, false);
        if (!forfeitedAccruals.isEmpty()) {
            journalEntryWritePlatformService
                    .linkSavingsReversalJournalEntries(forfeitedAccruals.stream().map(SavingsAccountTransaction::getId).toList());
        }
    }

    /**
     * AB-566: reverses every live accrual of a forfeited month. A month restored after its last day was processed has
     * nothing left accrued, so the run restarts on that day and re-books the month there. Returns the day to start on.
     */
    private static LocalDate applyForfeitures(final SavingsAccount savingsAccount, final LocalDate fromDate,
            final Map<LocalDate, Boolean> forfeitureByMonth, final List<SavingsAccountTransaction> forfeitedAccruals) {
        LocalDate startDate = fromDate;
        for (Map.Entry<LocalDate, Boolean> month : forfeitureByMonth.entrySet()) {
            final LocalDate monthStart = month.getKey();
            final LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
            if (Boolean.TRUE.equals(month.getValue())) {
                forfeitedAccruals.addAll(savingsAccount.reverseAccrualsBetween(monthStart, monthEnd));
            } else if (monthEnd.isBefore(startDate) && savingsAccount.hasOnlyReversedAccrualsBetween(monthStart, monthEnd)) {
                startDate = monthEnd;
            }
        }
        return startDate;
    }

    private static boolean isForfeited(final Map<LocalDate, Boolean> forfeitureByMonth, final LocalDate day) {
        return Boolean.TRUE.equals(forfeitureByMonth.get(day.withDayOfMonth(1)));
    }

    private static boolean hasLiveAccrualOn(final SavingsAccount savingsAccount, final LocalDate day) {
        return savingsAccount.retrieveOrderedAccrualTransactions().stream()
                .anyMatch(accrual -> !accrual.isReversed() && accrual.getTransactionDate().isEqual(day));
    }

    private static PostingPeriod periodContaining(final List<PostingPeriod> periods, final LocalDate day) {
        return periods.stream().filter(period -> period.getPeriodInterval().contains(day)).findFirst().orElse(null);
    }

    private static Money accruedInPeriodBefore(final SavingsAccount savingsAccount, final LocalDateInterval periodInterval,
            final LocalDate day) {
        Money accrued = Money.zero(savingsAccount.getCurrency());
        for (SavingsAccountTransaction accrual : savingsAccount.retrieveOrderedAccrualTransactions()) {
            if (!accrual.isReversed() && periodInterval.contains(accrual.getTransactionDate())
                    && accrual.getTransactionDate().isBefore(day)) {
                accrued = accrued.plus(accrual.getAmount(savingsAccount.getCurrency()));
            }
        }
        return accrued;
    }

}
