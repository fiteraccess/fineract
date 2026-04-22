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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavingsAccountDailyBalanceRepository
        extends JpaRepository<SavingsAccountDailyBalance, SavingsAccountDailyBalance.SavingsAccountDailyBalanceId> {

    /**
     * Find snapshot for a specific account and date.
     */
    Optional<SavingsAccountDailyBalance> findBySavingsAccountIdAndBalanceDate(Long savingsAccountId, LocalDate balanceDate);

    @Modifying
    @Query(value = "INSERT INTO m_savings_account_daily_balance (savings_account_id, balance_date, end_of_day_balance) "
            + "VALUES (?1, ?2, ?3) "
            + "ON CONFLICT (savings_account_id, balance_date) DO UPDATE SET end_of_day_balance = ?3", nativeQuery = true)
    void upsertDailyBalance(Long accountId, LocalDate balanceDate, BigDecimal balance);

    @Modifying
    @Query(value = "UPDATE m_savings_account_daily_balance SET end_of_day_balance = end_of_day_balance + ?3 "
            + "WHERE savings_account_id = ?1 AND balance_date > ?2", nativeQuery = true)
    void addDeltaAfterDate(Long accountId, LocalDate fromDate, BigDecimal delta);

    /**
     * Find all snapshots for a savings account within a date range, ordered by date ascending. Used for interest
     * calculation over a period.
     */
    @Query("SELECT db FROM SavingsAccountDailyBalance db WHERE db.savingsAccountId = :savingsAccountId "
            + "AND db.balanceDate >= :startDate AND db.balanceDate <= :endDate ORDER BY db.balanceDate ASC")
    List<SavingsAccountDailyBalance> findByAccountAndDateRange(@Param("savingsAccountId") Long savingsAccountId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Find all snapshots for a savings account after a given date (inclusive), ordered by date ascending. Used for
     * cascading backdated transaction deltas forward.
     */
    @Query("SELECT db FROM SavingsAccountDailyBalance db WHERE db.savingsAccountId = :savingsAccountId "
            + "AND db.balanceDate > :afterDate ORDER BY db.balanceDate ASC")
    List<SavingsAccountDailyBalance> findByAccountAfterDate(@Param("savingsAccountId") Long savingsAccountId,
            @Param("afterDate") LocalDate afterDate);

    /**
     * Find the latest daily balance snapshot on or before a given date. Used to determine the account balance at a
     * backdated transaction date without loading transaction entities.
     *
     * @param accountId
     *            the savings account ID
     * @param date
     *            find snapshot on or before this date
     * @param pageable
     *            use Pageable.ofSize(1) to get only the latest
     * @return list containing at most one snapshot (the latest on or before the date)
     */
    @Query("SELECT db FROM SavingsAccountDailyBalance db WHERE db.savingsAccountId = :accountId "
            + "AND db.balanceDate <= :date ORDER BY db.balanceDate DESC")
    List<SavingsAccountDailyBalance> findLatestOnOrBefore(@Param("accountId") Long accountId, @Param("date") LocalDate date,
            Pageable pageable);
}
