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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
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

@ExtendWith(MockitoExtension.class)
class AccountTransfersWritePlatformServiceReverseTest {

    @Mock
    private AccountTransferRepository accountTransferRepository;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private JsonCommand command;
    @Mock
    private AccountTransferTransaction transfer;
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
    void unknownTransferIsRejected() {
        when(accountTransferRepository.findById(eq(99L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverseAccountTransfer(99L, command)).isInstanceOf(AccountTransferNotFoundException.class);
    }

    @Test
    void alreadyReversedTransferIsRejected() {
        when(accountTransferRepository.findById(eq(7L))).thenReturn(Optional.of(transfer));
        when(transfer.isReversed()).thenReturn(true);

        assertThatThrownBy(() -> service.reverseAccountTransfer(7L, command)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("already reversed");
    }

    @Test
    void transferWithLoanLegIsRejected() {
        when(accountTransferRepository.findById(eq(7L))).thenReturn(Optional.of(transfer));
        when(transfer.isReversed()).thenReturn(false);
        when(transfer.getFromTransaction()).thenReturn(withdrawal);
        when(transfer.getToSavingsTransaction()).thenReturn(null);

        assertThatThrownBy(() -> service.reverseAccountTransfer(7L, command)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("savings-to-savings");
    }

    @Test
    void reversesBothLegsFlagsTransferAndReturnsBothReversalIds() {
        when(accountTransferRepository.findById(eq(7L))).thenReturn(Optional.of(transfer));
        when(transfer.isReversed()).thenReturn(false);
        when(transfer.getFromTransaction()).thenReturn(withdrawal);
        when(transfer.getToSavingsTransaction()).thenReturn(deposit);
        when(withdrawal.getSavingsAccount()).thenReturn(sourceAccount);
        when(withdrawal.getId()).thenReturn(100L);
        when(sourceAccount.getId()).thenReturn(5L);
        when(deposit.getSavingsAccount()).thenReturn(destinationAccount);
        when(deposit.getId()).thenReturn(200L);
        when(destinationAccount.getId()).thenReturn(6L);
        when(savingsAccountWritePlatformService.reverseTransaction(eq(6L), eq(200L), eq(true), eq(command)))
                .thenReturn(result(501L));
        when(savingsAccountWritePlatformService.reverseTransaction(eq(5L), eq(100L), eq(true), eq(command)))
                .thenReturn(result(502L));

        CommandProcessingResult result = service.reverseAccountTransfer(7L, command);

        verify(transfer).reverse();
        verify(accountTransferRepository).save(eq(transfer));
        assertThat(result.getResourceId()).isEqualTo(7L);
        assertThat(result.getChanges()).containsEntry("withdrawalReversalId", 502L).containsEntry("depositReversalId", 501L);
    }

    private CommandProcessingResult result(final Long reversalId) {
        return new CommandProcessingResultBuilder().withEntityId(reversalId).build();
    }
}
