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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.TreeMap;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;

/** Daily simple interest excludes every accrued-interest balance from principal. */
public final class GoalSavingsAccrual {

    private GoalSavingsAccrual() {}

    public static BigDecimal dailyAmount(BigDecimal principal, BigDecimal annualRate, int scale) {
        return principal.max(BigDecimal.ZERO).multiply(annualRate).divide(new BigDecimal("36500"), scale, RoundingMode.HALF_UP);
    }

    public static void accrue(SavingsAccount account, LocalDate through) {
        if (account.isGoalAccrualStopped() || account.getActivationDate() == null) {
            return;
        }
        // Today's principal can still change, so only completed business days earn interest.
        var lastCompletedDay = DateUtils.getBusinessLocalDate().minusDays(1);
        if (through.isAfter(lastCompletedDay)) {
            through = lastCompletedDay;
        }
        if (account.getGoalMaturityDate() != null && !through.isBefore(account.getGoalMaturityDate())) {
            through = account.getGoalMaturityDate().minusDays(1);
        }
        var changes = new TreeMap<LocalDate, BigDecimal>();
        var accruedDates = new HashSet<LocalDate>();
        for (var tx : account.getTransactions()) {
            if (tx.isReversed() || tx.isReversalTransaction()) {
                continue;
            }
            var type = tx.getTransactionType();
            if (type == SavingsAccountTransactionType.ACCRUAL) {
                accruedDates.add(tx.getTransactionDate());
            }
            if (type == SavingsAccountTransactionType.INTEREST_POSTING || type == SavingsAccountTransactionType.WITHHOLD_TAX) {
                continue;
            }
            if (tx.isCredit() || tx.isDebit()) {
                var amount = tx.getAmount(account.getCurrency()).getAmount();
                changes.merge(tx.getTransactionDate(), tx.isCredit() ? amount : amount.negate(), BigDecimal::add);
            }
        }
        var start = account.getAccruedTillDate() == null ? account.getActivationDate() : account.getAccruedTillDate().plusDays(1);
        var principal = changes.headMap(start, false).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        var scale = account.getCurrency().getDigitsAfterDecimal();
        for (var day = start; !day.isAfter(through); day = day.plusDays(1)) {
            principal = principal.add(changes.getOrDefault(day, BigDecimal.ZERO));
            if (!accruedDates.contains(day)) {
                var amount = dailyAmount(principal, account.getNominalAnnualInterestRate(), scale);
                if (amount.signum() > 0) {
                    var money = Money.of(account.getCurrency(), amount);
                    var tx = SavingsAccountTransaction.accrual(account, account.office(), day, money, false, null);
                    tx.setRunningBalance(Money.of(account.getCurrency(), principal));
                    tx.setOverdraftAmount(money);
                    account.addTransaction(tx);
                }
            }
            account.setAccruedTillDate(day);
        }
    }
}
