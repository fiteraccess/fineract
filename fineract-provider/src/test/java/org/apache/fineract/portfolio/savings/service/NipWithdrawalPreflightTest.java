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

import static org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity.VAT_PAYABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccount;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.ReferenceTransaction;
import org.junit.jupiter.api.Test;

class NipWithdrawalPreflightTest {

    private final NipSwitchAccountingConfigurationProvider switchConfigurationProvider = mock(
            NipSwitchAccountingConfigurationProvider.class);
    private final FinancialActivityAccountRepositoryWrapper financialActivityAccountRepositoryWrapper = mock(
            FinancialActivityAccountRepositoryWrapper.class);
    private final NipWithdrawalPreflight preflight = new NipWithdrawalPreflight(switchConfigurationProvider,
            financialActivityAccountRepositoryWrapper);

    @Test
    void requiresSwitchConfigurationWithoutVatMappingForCommissionOnlyRequest() {
        preflight.validate("NIBSS", List.of(reference(SavingsAccountTransactionType.COMMISSION)));

        verify(switchConfigurationProvider).require("NIBSS");
        verify(financialActivityAccountRepositoryWrapper, never()).findByFinancialActivityTypeWithNotFoundDetection(VAT_PAYABLE.getValue());
    }

    @Test
    void feeFreeRequestDoesNotRequireVatMapping() {
        preflight.validate("NIBSS", List.of());

        verify(switchConfigurationProvider).require("NIBSS");
        verify(financialActivityAccountRepositoryWrapper, never()).findByFinancialActivityTypeWithNotFoundDetection(VAT_PAYABLE.getValue());
    }

    @Test
    void requiresVatPayableMappingForVatRequest() {
        FinancialActivityAccount mapping = mock(FinancialActivityAccount.class);
        GLAccount glAccount = mock(GLAccount.class);
        when(mapping.getGlAccount()).thenReturn(glAccount);
        when(glAccount.isDetailAccount()).thenReturn(true);
        when(financialActivityAccountRepositoryWrapper.findByFinancialActivityTypeWithNotFoundDetection(VAT_PAYABLE.getValue()))
                .thenReturn(mapping);

        preflight.validate("NIBSS", List.of(reference(SavingsAccountTransactionType.VAT)));

        verify(switchConfigurationProvider).require("NIBSS");
        verify(financialActivityAccountRepositoryWrapper).findByFinancialActivityTypeWithNotFoundDetection(VAT_PAYABLE.getValue());
    }

    @Test
    void rejectsUnusableVatPayableMapping() {
        FinancialActivityAccount mapping = mock(FinancialActivityAccount.class);
        GLAccount glAccount = mock(GLAccount.class);
        when(mapping.getGlAccount()).thenReturn(glAccount);
        when(financialActivityAccountRepositoryWrapper.findByFinancialActivityTypeWithNotFoundDetection(VAT_PAYABLE.getValue()))
                .thenReturn(mapping);

        assertThatThrownBy(() -> preflight.validate("NIBSS", List.of(reference(SavingsAccountTransactionType.VAT))))
                .hasMessageContaining("VAT_PAYABLE");
    }

    @Test
    void switchConfigurationFailureShortCircuitsVatLookup() {
        RuntimeException failure = new RuntimeException("missing switch");
        when(switchConfigurationProvider.require("NIBSS")).thenThrow(failure);

        assertThatThrownBy(() -> preflight.validate("NIBSS", List.of(reference(SavingsAccountTransactionType.VAT)))).isSameAs(failure);
        verify(financialActivityAccountRepositoryWrapper, never()).findByFinancialActivityTypeWithNotFoundDetection(VAT_PAYABLE.getValue());
    }

    private static ReferenceTransaction reference(SavingsAccountTransactionType type) {
        return new ReferenceTransaction(type, BigDecimal.ONE, "test", null);
    }
}
