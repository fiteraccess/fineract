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
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTransactionDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SavingsAccountingBridgeDataHelperTest {

    @Mock
    private SavingsAccount account;
    @Mock
    private SavingsProduct savingsProduct;
    @Mock
    private MonetaryCurrency currency;
    @Mock
    private SavingsAccountTransaction firstTransaction;
    @Mock
    private SavingsAccountTransaction secondTransaction;
    @Mock
    private SavingsAccountTransaction thirdTransaction;

    @Test
    void buildAccountingBridgeDataShouldUseProvidedTransactions() {
        when(account.getCurrency()).thenReturn(currency);
        when(currency.getCode()).thenReturn("USD");
        when(account.getId()).thenReturn(11L);
        when(account.productId()).thenReturn(22L);
        when(account.officeId()).thenReturn(33L);
        when(account.savingsProduct()).thenReturn(savingsProduct);
        when(savingsProduct.isCashBasedAccountingEnabled()).thenReturn(true);
        when(savingsProduct.isAccrualBasedAccountingEnabled()).thenReturn(false);
        SavingsAccountingBridgeTransactionDTO bridgeTransaction = new SavingsAccountingBridgeTransactionDTO();
        bridgeTransaction.setId(44L);
        when(firstTransaction.toAccountingBridgeDTO("USD")).thenReturn(bridgeTransaction);

        SavingsAccountingBridgeDTO result = SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(account, List.of(firstTransaction), true);

        assertThat(result.getSavingsId()).isEqualTo(11L);
        assertThat(result.getSavingsProductId()).isEqualTo(22L);
        assertThat(result.getCurrencyCode()).isEqualTo("USD");
        assertThat(result.getOfficeId()).isEqualTo(33L);
        assertThat(result.isCashBasedAccountingEnabled()).isTrue();
        assertThat(result.isAccrualBasedAccountingEnabled()).isFalse();
        assertThat(result.isAccountTransfer()).isTrue();
        assertThat(result.getNewSavingsTransactions()).containsExactly(bridgeTransaction);
    }

    @Test
    void buildAccountingBridgeDataShouldCarrySuppliedCommissionAllocation() {
        when(account.getCurrency()).thenReturn(currency);
        when(currency.getCode()).thenReturn("NGN");
        when(account.savingsProduct()).thenReturn(savingsProduct);
        SavingsAccountingBridgeTransactionDTO bridgeTransaction = new SavingsAccountingBridgeTransactionDTO();
        when(firstTransaction.toAccountingBridgeDTO("NGN")).thenReturn(bridgeTransaction);
        ReferenceTransaction referenceTransaction = new ReferenceTransaction(SavingsAccountTransactionType.COMMISSION, BigDecimal.TEN,
                "Commission", new ReferenceTransaction.CommissionBreakdown(
                        new ReferenceTransaction.CommissionBreakdownLeg(BigDecimal.valueOf(2)),
                        new ReferenceTransaction.CommissionBreakdownLeg(BigDecimal.valueOf(8))));

        SavingsAccountingBridgeDTO result = SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(account, firstTransaction,
                referenceTransaction, false);

        assertThat(result.getNewSavingsTransactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.getCommissionAllocation().switchFeeAmount()).isEqualByComparingTo("2");
            assertThat(transaction.getCommissionAllocation().bankCommissionAmount()).isEqualByComparingTo("8");
        });
    }

    @Test
    void findNewTransactionsShouldFilterExistingAndReversedTransactions() {
        when(account.getTransactions()).thenReturn(List.of(firstTransaction, secondTransaction, thirdTransaction));
        when(firstTransaction.getId()).thenReturn(1L);
        when(firstTransaction.isReversed()).thenReturn(false);
        when(secondTransaction.getId()).thenReturn(2L);
        when(secondTransaction.isReversed()).thenReturn(true);
        when(thirdTransaction.getId()).thenReturn(3L);
        when(thirdTransaction.isReversed()).thenReturn(false);

        List<SavingsAccountTransaction> result = SavingsAccountingBridgeDataHelper.findNewTransactions(account, Set.of(1L), Set.of(), false);

        assertThat(result).containsExactly(secondTransaction, thirdTransaction);
    }

    @Test
    void findNewTransactionsShouldUsePivotTransactionsWhenBackdated() {
        when(account.getSavingsAccountTransactionsWithPivotConfig()).thenReturn(List.of(secondTransaction));
        when(secondTransaction.getId()).thenReturn(9L);
        when(secondTransaction.isReversed()).thenReturn(false);

        List<SavingsAccountTransaction> result = SavingsAccountingBridgeDataHelper.findNewTransactions(account, Set.of(), Set.of(), true);

        assertThat(result).containsExactly(secondTransaction);
    }
}
