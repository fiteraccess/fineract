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
package org.apache.fineract.portfolio.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferTransaction;
import org.apache.fineract.portfolio.account.exception.AccountTransferNotFoundException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * command=undo takes the same id as the transfer list, the retrieve endpoint and command=reverse: the transfer
 * transaction id. It must act on that one transaction only, never on every transaction under the same details row.
 */
@ExtendWith(MockitoExtension.class)
class AccountTransfersWritePlatformServiceUndoTest {

    @Mock
    private AccountTransferRepository accountTransferRepository;
    @Mock
    private AccountTransferDetailRepository accountTransferDetailRepository;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private JsonCommand command;
    @Mock
    private AccountTransferTransaction transfer;
    @Mock
    private AccountTransferDetails details;
    @Mock
    private SavingsAccountTransaction withdrawal;
    @Mock
    private SavingsAccountTransaction deposit;
    @Mock
    private SavingsAccount sourceAccount;
    @Mock
    private SavingsAccount destinationAccount;

    @InjectMocks
    private AccountTransfersWritePlatformServiceImpl service;

    @Test
    void idIsResolvedAsTransferTransactionNotAsDetails() {
        when(command.entityId()).thenReturn(99L);
        when(accountTransferRepository.findById(eq(99L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.undo(command)).isInstanceOf(AccountTransferNotFoundException.class);

        verifyNoInteractions(accountTransferDetailRepository);
    }

    @Test
    void alreadyReversedTransferIsRejected() {
        when(command.entityId()).thenReturn(7L);
        when(accountTransferRepository.findById(eq(7L))).thenReturn(Optional.of(transfer));
        when(transfer.isReversed()).thenReturn(true);

        assertThatThrownBy(() -> service.undo(command)).isInstanceOf(GeneralPlatformDomainRuleException.class);

        verify(savingsAccountWritePlatformService, never()).undoTransaction(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void undoesOnlyTheSelectedTransferAndReturnsItsId() {
        when(command.entityId()).thenReturn(7L);
        when(accountTransferRepository.findById(eq(7L))).thenReturn(Optional.of(transfer));
        when(transfer.isReversed()).thenReturn(false);
        when(transfer.getId()).thenReturn(7L);
        when(transfer.accountTransferDetails()).thenReturn(details);
        when(details.fromSavingsAccount()).thenReturn(sourceAccount);
        when(details.toSavingsAccount()).thenReturn(destinationAccount);
        when(transfer.getFromSavingsTransaction()).thenReturn(withdrawal);
        when(transfer.getToSavingsTransaction()).thenReturn(deposit);
        when(withdrawal.getSavingsAccount()).thenReturn(sourceAccount);
        when(withdrawal.getId()).thenReturn(100L);
        when(sourceAccount.getId()).thenReturn(5L);
        when(deposit.getSavingsAccount()).thenReturn(destinationAccount);
        when(deposit.getId()).thenReturn(200L);
        when(destinationAccount.getId()).thenReturn(6L);

        CommandProcessingResult result = service.undo(command);

        verify(savingsAccountWritePlatformService).undoTransaction(eq(5L), eq(100L), eq(true));
        verify(savingsAccountWritePlatformService).undoTransaction(eq(6L), eq(200L), eq(true));
        verify(savingsAccountWritePlatformService, times(2)).undoTransaction(anyLong(), anyLong(), anyBoolean());
        verify(transfer).reverse();
        verify(details, never()).getAccountTransferTransactions();
        verifyNoInteractions(accountTransferDetailRepository);
        assertThat(result.getResourceId()).isEqualTo(7L);
    }
}
