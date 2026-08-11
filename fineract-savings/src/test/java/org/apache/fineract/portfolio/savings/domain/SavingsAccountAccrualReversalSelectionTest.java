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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.Test;

class SavingsAccountAccrualReversalSelectionTest {

    private static final LocalDate FROM_DATE = LocalDate.of(2026, 8, 5);

    @Test
    void selectsOnlyLiveAccrualsOnOrAfterTheDate() {
        SavingsAccountTransaction accrualBefore = transaction(SavingsAccountTransactionType.ACCRUAL, LocalDate.of(2026, 8, 4), false);
        SavingsAccountTransaction accrualOnDate = transaction(SavingsAccountTransactionType.ACCRUAL, FROM_DATE, false);
        SavingsAccountTransaction accrualAfter = transaction(SavingsAccountTransactionType.ACCRUAL, LocalDate.of(2026, 8, 9), false);
        SavingsAccountTransaction reversedAccrual = transaction(SavingsAccountTransactionType.ACCRUAL, LocalDate.of(2026, 8, 6), true);
        SavingsAccountTransaction deposit = transaction(SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 8, 6), false);

        List<SavingsAccountTransaction> selected = SavingsAccount
                .selectAccrualsToReverse(List.of(accrualBefore, accrualOnDate, accrualAfter, reversedAccrual, deposit), FROM_DATE);

        assertThat(selected).containsExactly(accrualOnDate, accrualAfter);
    }

    @Test
    void selectsNothingWhenNoAccrualsExist() {
        SavingsAccountTransaction deposit = transaction(SavingsAccountTransactionType.DEPOSIT, FROM_DATE, false);
        SavingsAccountTransaction withdrawal = transaction(SavingsAccountTransactionType.WITHDRAWAL, FROM_DATE, false);

        assertThat(SavingsAccount.selectAccrualsToReverse(List.of(deposit, withdrawal), FROM_DATE)).isEmpty();
    }

    private SavingsAccountTransaction transaction(final SavingsAccountTransactionType type, final LocalDate date, final boolean reversed) {
        SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        lenient().when(transaction.getTransactionType()).thenReturn(type);
        lenient().when(transaction.getDateOf()).thenReturn(date);
        lenient().when(transaction.isNotReversed()).thenReturn(!reversed);
        return transaction;
    }
}
