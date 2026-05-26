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
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavingsAccountDailyBalanceRepository
        extends JpaRepository<SavingsAccountDailyBalance, SavingsAccountDailyBalance.SavingsAccountDailyBalanceId> {

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
     * Find all snapshots for a set of savings accounts within a date range, ordered by account then date ascending.
     * Used by the interest tasklet to fetch a page's worth of snapshots in one round-trip; callers group by
     * {@code savingsAccountId} and feed each slice into
     * {@link org.apache.fineract.portfolio.savings.service.SnapshotInterestCalculationService} batched overload.
     */
    @Query("SELECT db FROM SavingsAccountDailyBalance db " + "WHERE db.savingsAccountId IN :accountIds "
            + "AND db.balanceDate BETWEEN :startDate AND :endDate " + "ORDER BY db.savingsAccountId, db.balanceDate ASC")
    List<SavingsAccountDailyBalance> findByAccountsAndDateRange(@Param("accountIds") Collection<Long> accountIds,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

}
