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

import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsAccrualWritePlatformServiceImpl implements SavingsAccrualWritePlatformService {

    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final SavingsAccountDomainService savingsAccountDomainService;

    @Transactional
    @Override
    public void addAccrualEntries(LocalDate tillDate) throws JobExecutionException {
        final List<SavingsAccrualData> savingsAccrualData = savingsAccountReadPlatformService.retrievePeriodicAccrualData(tillDate, null);
        final Integer financialYearBeginningMonth = configurationDomainService.retrieveFinancialYearBeginningMonth();
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final MathContext mc = MoneyHelper.getMathContext();

        List<Throwable> errors = new ArrayList<>();
        for (SavingsAccrualData savingsAccrual : savingsAccrualData) {
            try {
                if (savingsAccrual.getDepositType().isSavingsDeposit() && savingsAccrual.getIsAllowOverdraft()) {
                    if (!savingsAccrual.getIsTypeInterestReceivable()) {
                        continue;
                    }
                }
                SavingsAccount savingsAccount = savingsAccountAssembler.assembleFrom(savingsAccrual.getId(), false);
                LocalDate fromDate = savingsAccrual.getAccruedTill();
                if (fromDate == null) {
                    fromDate = savingsAccount.getActivationDate();
                }
                log.debug("Processing savings account {} from date {} till date {}", savingsAccrual.getAccountNo(), fromDate, tillDate);
                addAccrualTransactions(savingsAccount, fromDate, tillDate, financialYearBeginningMonth,
                        isSavingsInterestPostingAtCurrentPeriodEnd, mc, null);
            } catch (Exception e) {
                log.error("Failed to add accrual transaction for savings {} : {}", savingsAccrual.getAccountNo(), e.getMessage());
                errors.add(e.getCause());
            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
    }

    /**
     * AB-401: books each day the interest the posting engine has earned so far in that day's posting period, less what
     * is already accrued in it, so a period's accruals always add up to what interest posting credits for it.
     */
    private void addAccrualTransactions(SavingsAccount savingsAccount, final LocalDate fromDate, final LocalDate tillDate,
            final Integer financialYearBeginningMonth, final boolean isSavingsInterestPostingAtCurrentPeriodEnd, final MathContext mc,
            final Function<LocalDate, String> refNoProvider) {
        final Set<Long> existingTransactionIds = new HashSet<>(savingsAccount.findExistingTransactionIds());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(savingsAccount.findExistingReversedTransactionIds());

        LocalDate accruedTillDate = null;
        for (LocalDate day = fromDate; !day.isAfter(tillDate) && !DateUtils.isDateInTheFuture(day); day = day.plusDays(1)) {
            accruedTillDate = day;
            if (hasLiveAccrualOn(savingsAccount, day)) {
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
