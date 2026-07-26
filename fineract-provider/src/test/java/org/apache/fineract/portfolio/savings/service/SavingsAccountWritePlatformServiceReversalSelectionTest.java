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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.Test;

class SavingsAccountWritePlatformServiceReversalSelectionTest {

    @Test
    void shouldExcludeCommissionAndVatFromImplicitBulkReversal() {
        SavingsAccountTransaction principal = transaction(10L, SavingsAccountTransactionType.WITHDRAWAL);
        SavingsAccountTransaction emtLevy = transaction(11L, SavingsAccountTransactionType.EMT_LEVY);
        SavingsAccountTransaction commission = transaction(12L, SavingsAccountTransactionType.COMMISSION);
        SavingsAccountTransaction vat = transaction(13L, SavingsAccountTransactionType.VAT);

        List<SavingsAccountTransaction> selected = SavingsAccountWritePlatformServiceJpaRepositoryImpl
                .selectTransactionsForBulkReversal(10L, List.of(principal, emtLevy, commission, vat));

        assertThat(selected).containsExactly(principal, emtLevy);
    }

    @Test
    void shouldRetainExplicitlyRequestedCommissionWithoutImplicitlySelectingVat() {
        SavingsAccountTransaction principal = transaction(10L, SavingsAccountTransactionType.WITHDRAWAL);
        SavingsAccountTransaction commission = transaction(12L, SavingsAccountTransactionType.COMMISSION);
        SavingsAccountTransaction vat = transaction(13L, SavingsAccountTransactionType.VAT);

        List<SavingsAccountTransaction> selected = SavingsAccountWritePlatformServiceJpaRepositoryImpl
                .selectTransactionsForBulkReversal(12L, List.of(principal, commission, vat));

        assertThat(selected).containsExactly(principal, commission);
    }

    private SavingsAccountTransaction transaction(final Long id, final SavingsAccountTransactionType type) {
        SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        when(transaction.getId()).thenReturn(id);
        when(transaction.getTransactionType()).thenReturn(type);
        return transaction;
    }
}
