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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stores end-of-day balance snapshots for savings accounts. Each row represents the end-of-day balance for a specific
 * account on a specific date. Used for O(1) interest calculation instead of replaying all transactions.
 */
@Entity
@Table(name = "m_savings_account_daily_balance")
@IdClass(SavingsAccountDailyBalance.SavingsAccountDailyBalanceId.class)
@Getter
@Setter
@NoArgsConstructor
public class SavingsAccountDailyBalance {

    @Id
    @Column(name = "savings_account_id", nullable = false)
    private Long savingsAccountId;

    @Id
    @Column(name = "balance_date", nullable = false)
    private LocalDate balanceDate;

    @Column(name = "end_of_day_balance", scale = 6, precision = 19, nullable = false)
    private BigDecimal endOfDayBalance;

    @Version
    @Column(name = "version")
    private Long version;

    public SavingsAccountDailyBalance(final Long savingsAccountId, final LocalDate balanceDate, final BigDecimal endOfDayBalance) {
        this.savingsAccountId = savingsAccountId;
        this.balanceDate = balanceDate;
        this.endOfDayBalance = endOfDayBalance;
    }

    /**
     * Adds a delta to the current end-of-day balance. Used when cascading backdated transaction adjustments forward.
     *
     * @param delta
     *            the amount to add (can be negative for withdrawals)
     */
    public void addDelta(final BigDecimal delta) {
        this.endOfDayBalance = this.endOfDayBalance.add(delta);
    }

    /**
     * Composite primary key for SavingsAccountDailyBalance.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SavingsAccountDailyBalanceId implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long savingsAccountId;
        private LocalDate balanceDate;
    }
}
