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

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDailyBalanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyBalanceSnapshotServiceImplTest {

    @Mock
    private SavingsAccountDailyBalanceRepository dailyBalanceRepository;

    @InjectMocks
    private DailyBalanceSnapshotServiceImpl subject;

    @Test
    void updateSnapshotShouldUpsertDailyBalance() {
        Long accountId = 1L;
        LocalDate transactionDate = LocalDate.of(2026, 3, 23);
        BigDecimal balance = new BigDecimal("123.45");

        subject.updateSnapshot(accountId, transactionDate, balance);

        verify(dailyBalanceRepository).upsertDailyBalance(accountId, transactionDate, balance);
    }

    @Test
    void handleBackdatedTransactionShouldUpsertThenCascadeDelta() {
        Long accountId = 2L;
        LocalDate backdatedDate = LocalDate.of(2026, 3, 1);
        BigDecimal delta = new BigDecimal("10.00");
        BigDecimal newBalanceOnDate = new BigDecimal("250.00");

        subject.handleBackdatedTransaction(accountId, backdatedDate, delta, newBalanceOnDate);

        InOrder inOrder = inOrder(dailyBalanceRepository);
        inOrder.verify(dailyBalanceRepository).upsertDailyBalance(accountId, backdatedDate, newBalanceOnDate);
        inOrder.verify(dailyBalanceRepository).addDeltaAfterDate(accountId, backdatedDate, delta);
    }
}
