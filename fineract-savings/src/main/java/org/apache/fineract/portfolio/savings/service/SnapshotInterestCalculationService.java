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
import java.util.List;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDailyBalance;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;

/**
 * Service for calculating interest on savings accounts using daily balance snapshots instead of replaying all
 * transactions. This provides O(days_with_snapshots) interest calculation instead of O(total_transactions).
 */
public interface SnapshotInterestCalculationService {

    /**
     * Calculates interest for a savings account using daily balance snapshots. Reads snapshots for the interest
     * calculation period and builds posting periods from them, replacing the O(N) recalculateDailyBalances() call.
     *
     * @param account
     *            the savings account
     * @param mc
     *            the math context for rounding
     * @param upToDate
     *            the end date for interest calculation
     * @param isInterestTransfer
     *            whether this is an interest transfer
     * @param isSavingsInterestPostingAtCurrentPeriodEnd
     *            global config flag
     * @param financialYearBeginningMonth
     *            global config value
     * @return the list of posting periods with calculated interest
     */
    List<PostingPeriod> calculateInterestFromSnapshots(SavingsAccount account, MathContext mc, LocalDate upToDate,
            boolean isInterestTransfer, boolean isSavingsInterestPostingAtCurrentPeriodEnd, Integer financialYearBeginningMonth);

    /**
     * Overload that accepts a pre-fetched list of snapshots for the account, skipping the per-account repository
     * round-trip. Intended for callers (e.g. the interest tasklet) that batch-fetch snapshots for a page of accounts
     * via {@code SavingsAccountDailyBalanceRepository#findByAccountsAndDateRange} and then pass each account's slice
     * here.
     *
     * <p>
     * The supplied list is expected to cover {@code [account.getStartInterestCalculationDate(), upToDate]} for the
     * given account; entries outside the relevant period are ignored.
     */
    List<PostingPeriod> calculateInterestFromSnapshots(SavingsAccount account, MathContext mc, LocalDate upToDate,
            boolean isInterestTransfer, boolean isSavingsInterestPostingAtCurrentPeriodEnd, Integer financialYearBeginningMonth,
            List<SavingsAccountDailyBalance> preFetchedSnapshots);
}
