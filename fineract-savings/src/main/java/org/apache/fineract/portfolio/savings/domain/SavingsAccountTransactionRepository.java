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

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavingsAccountTransactionRepository
        extends JpaRepository<SavingsAccountTransaction, Long>, JpaSpecificationExecutor<SavingsAccountTransaction> {

    @Query("select sat from SavingsAccountTransaction sat where sat.id = :transactionId and sat.savingsAccount.id = :savingsId")
    SavingsAccountTransaction findOneByIdAndSavingsAccountId(@Param("transactionId") Long transactionId,
            @Param("savingsId") Long savingsId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select st from SavingsAccountTransaction st where st.savingsAccount = :savingsAccount and st.dateOf >= :transactionDate order by st.dateOf,st.createdDate,st.id")
    List<SavingsAccountTransaction> findTransactionsAfterPivotDate(@Param("savingsAccount") SavingsAccount savingsAccount,
            @Param("transactionDate") LocalDate transactionDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select st from SavingsAccountTransaction st where st.savingsAccount = :savingsAccount and st.dateOf = :date and st.reversalTransaction <> 1 and st.reversed <> 1 order by st.id")
    List<SavingsAccountTransaction> findTransactionRunningBalanceBeforePivotDate(@Param("savingsAccount") SavingsAccount savingsAccount,
            @Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SavingsAccountTransaction> findBySavingsAccount(@Param("savingsAccount") SavingsAccount savingsAccount);

    List<SavingsAccountTransaction> findByRefNo(@Param("refNo") String refNo);

    @Query("select sat from SavingsAccountTransaction sat where sat.savingsAccount.id = :savingsId and sat.dateOf <= :transactionDate and sat.reversed=false")
    List<SavingsAccountTransaction> findBySavingsAccountIdAndLessThanDateOfAndReversedIsFalse(@Param("savingsId") Long savingsId,
            @Param("transactionDate") LocalDate transactionDate, Pageable pageable);

    /**
     * Bulk-reverse all non-reversed accrual transactions for a given account on or after the specified date. This
     * replaces the O(N) in-memory iteration in {@code SavingsAccount.accrualsForSavingsReverse()} with a single O(1)
     * indexed UPDATE statement.
     *
     * @param savingsAccountId
     *            the savings account ID
     * @param transactionDate
     *            reverse accruals on or after this date
     * @return the number of accrual transactions reversed
     */
    @Modifying
    @Query("UPDATE SavingsAccountTransaction sat SET sat.reversed = true WHERE sat.savingsAccount.id = :savingsAccountId AND sat.typeOf = 10 AND sat.dateOf >= :transactionDate AND sat.reversed = false")
    int reverseAccrualTransactions(@Param("savingsAccountId") Long savingsAccountId, @Param("transactionDate") LocalDate transactionDate);

    /**
     * Batch-update running balances for all non-reversed transactions on or after the given date. Used by the optimized
     * backdated transaction path to adjust running balances in O(1) DB round-trip instead of loading K entities into
     * memory.
     *
     * @param accountId
     *            the savings account ID
     * @param fromDate
     *            update transactions on or after this date
     * @param delta
     *            the amount to add (positive for deposit, negative for withdrawal)
     * @return number of transactions updated
     */
    @Modifying
    @Query("UPDATE SavingsAccountTransaction sat SET sat.runningBalance = sat.runningBalance + :delta "
            + "WHERE sat.savingsAccount.id = :accountId AND sat.dateOf >= :fromDate AND sat.reversed = false "
            + "AND sat.reversalTransaction = false")
    int updateRunningBalancesAfterDate(@Param("accountId") Long accountId, @Param("fromDate") LocalDate fromDate,
            @Param("delta") BigDecimal delta);

    /**
     * Find the minimum running balance for all non-reversed transactions on or after a date. Used by the optimized
     * backdated withdrawal path to validate that no historical point goes negative without loading any entities into
     * memory.
     *
     * @param accountId
     *            the savings account ID
     * @param fromDate
     *            check transactions on or after this date
     * @return the minimum running balance, or null if no transactions exist
     */
    @Query("SELECT MIN(sat.runningBalance) FROM SavingsAccountTransaction sat "
            + "WHERE sat.savingsAccount.id = :accountId AND sat.dateOf >= :fromDate "
            + "AND sat.reversed = false AND sat.reversalTransaction = false")
    BigDecimal findMinRunningBalanceAfterDate(@Param("accountId") Long accountId, @Param("fromDate") LocalDate fromDate);

    /**
     * Calculate the account balance as of just before a given date by summing all non-reversed transactions before that
     * date. Credits (deposit=1, interest_posting=3, dividend_payout=12, amount_release=13) are positive; debits
     * (withdrawal=2, withdrawal_fee=5, annual_fee=6, pay_charge=7, waive_charge=8, withhold_tax=17) are negative.
     *
     * Used as a fallback when no daily balance snapshot exists for the backdated date.
     *
     * @param accountId
     *            the savings account ID
     * @param beforeDate
     *            calculate balance before (exclusive) this date
     * @return the calculated balance
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN sat.typeOf IN (1, 3, 12, 13) THEN sat.amount "
            + "WHEN sat.typeOf IN (2, 5, 6, 7, 8, 17) THEN -sat.amount ELSE 0 END), 0) " + "FROM SavingsAccountTransaction sat "
            + "WHERE sat.savingsAccount.id = :accountId AND sat.dateOf < :beforeDate "
            + "AND sat.reversed = false AND sat.reversalTransaction = false")
    BigDecimal calculateBalanceBefore(@Param("accountId") Long accountId, @Param("beforeDate") LocalDate beforeDate);
}
