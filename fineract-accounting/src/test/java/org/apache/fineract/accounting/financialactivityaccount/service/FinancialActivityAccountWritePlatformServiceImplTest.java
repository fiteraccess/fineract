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
package org.apache.fineract.accounting.financialactivityaccount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccount;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.financialactivityaccount.exception.FinancialActivityAccountInvalidException;
import org.apache.fineract.accounting.financialactivityaccount.serialization.FinancialActivityAccountDataValidator;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Each EOD bridge activity maps only to a GL of its declared type, the way every financial activity is guarded. */
class FinancialActivityAccountWritePlatformServiceImplTest {

    private static final long GL_ACCOUNT_ID = 11L;

    private FinancialActivityAccountRepositoryWrapper repository;
    private GLAccountRepositoryWrapper glAccounts;
    private FinancialActivityAccountWritePlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialActivityAccountRepositoryWrapper.class);
        glAccounts = mock(GLAccountRepositoryWrapper.class);
        service = new FinancialActivityAccountWritePlatformServiceImpl(repository, mock(FinancialActivityAccountDataValidator.class),
                glAccounts);
    }

    @Test
    void mapsAnEodBridgeToAGlOfItsDeclaredType() {
        stubGlAccount(GLAccountType.LIABILITY);

        service.createFinancialActivityAccountMapping(command(FinancialActivity.EOD_DEPOSIT_BRIDGE));

        final ArgumentCaptor<FinancialActivityAccount> saved = ArgumentCaptor.forClass(FinancialActivityAccount.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFinancialActivityType()).isEqualTo(FinancialActivity.EOD_DEPOSIT_BRIDGE.getValue());
    }

    @Test
    void refusesAnEodBridgeOnAGlOfAnotherType() {
        stubGlAccount(GLAccountType.LIABILITY);

        assertThatThrownBy(() -> service.createFinancialActivityAccountMapping(command(FinancialActivity.EOD_INTEREST_BRIDGE)))
                .isInstanceOf(FinancialActivityAccountInvalidException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    private void stubGlAccount(final GLAccountType type) {
        final GLAccount glAccount = mock(GLAccount.class);
        when(glAccount.getType()).thenReturn(type.getValue());
        when(glAccounts.findOneWithNotFoundDetection(GL_ACCOUNT_ID)).thenReturn(glAccount);
    }

    private static JsonCommand command(final FinancialActivity activity) {
        final JsonCommand command = mock(JsonCommand.class);
        when(command.json()).thenReturn("{}");
        when(command.integerValueSansLocaleOfParameterNamed("financialActivityId")).thenReturn(activity.getValue());
        when(command.longValueOfParameterNamed("glAccountId")).thenReturn(GL_ACCOUNT_ID);
        return command;
    }
}
