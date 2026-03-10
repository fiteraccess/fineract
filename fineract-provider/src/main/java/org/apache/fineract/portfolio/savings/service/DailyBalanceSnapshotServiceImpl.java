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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDailyBalance;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDailyBalanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DailyBalanceSnapshotServiceImpl implements DailyBalanceSnapshotService {

    private final SavingsAccountDailyBalanceRepository dailyBalanceRepository;

    @Override
    @Transactional
    public void updateSnapshot(final Long savingsAccountId, final LocalDate transactionDate, final BigDecimal newAccountBalance) {
        final Optional<SavingsAccountDailyBalance> existing = dailyBalanceRepository.findBySavingsAccountIdAndBalanceDate(savingsAccountId,
                transactionDate);
        if (existing.isPresent()) {
            existing.get().setEndOfDayBalance(newAccountBalance);
        } else {
            dailyBalanceRepository.save(new SavingsAccountDailyBalance(savingsAccountId, transactionDate, newAccountBalance));
        }
    }

    @Override
    @Transactional
    public void handleBackdatedTransaction(final Long savingsAccountId, final LocalDate backdatedDate, final BigDecimal delta,
            final BigDecimal newBalanceOnDate) {
        // Update or create snapshot for the backdated date
        final Optional<SavingsAccountDailyBalance> existing = dailyBalanceRepository.findBySavingsAccountIdAndBalanceDate(savingsAccountId,
                backdatedDate);
        if (existing.isPresent()) {
            existing.get().addDelta(delta);
        } else {
            dailyBalanceRepository.save(new SavingsAccountDailyBalance(savingsAccountId, backdatedDate, newBalanceOnDate));
        }

        // Cascade the delta forward to all subsequent snapshots — O(days_with_snapshots)
        final List<SavingsAccountDailyBalance> subsequentSnapshots = dailyBalanceRepository.findByAccountAfterDate(savingsAccountId,
                backdatedDate);
        for (final SavingsAccountDailyBalance snapshot : subsequentSnapshots) {
            snapshot.addDelta(delta);
        }
    }
}
