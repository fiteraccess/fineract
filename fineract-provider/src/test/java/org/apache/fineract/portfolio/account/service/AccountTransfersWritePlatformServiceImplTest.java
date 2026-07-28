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

import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountTypeParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountTypeParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferAmountParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferDateParamName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.AccountTransfersDataValidator;
import org.apache.fineract.portfolio.account.domain.AccountTransferAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferTransaction;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.loanaccount.data.PaidInAdvanceData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountTransfersWritePlatformServiceImplTest {

    @Mock
    private AccountTransfersDataValidator validator;
    @Mock
    private AccountTransferAssembler accountTransferAssembler;
    @Mock
    private AccountTransferRepository accountTransferRepository;
    @Mock
    private SavingsAccountAssembler savingsAccountAssembler;
    @Mock
    private SavingsAccountDomainService savingsAccountDomainService;
    @Mock
    private LoanAssembler loanAssembler;
    @Mock
    private LoanAccountDomainService loanAccountDomainService;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private AccountTransferDetailRepository accountTransferDetailRepository;
    @Mock
    private LoanReadPlatformService loanReadPlatformService;
    @Mock
    private GSIMRepositoy gsimRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private ExternalIdFactory externalIdFactory;
    @Mock
    private FineractProperties fineractProperties;

    private AccountTransfersWritePlatformServiceImpl underTest;

    @BeforeEach
    void setUp() {
        underTest = new AccountTransfersWritePlatformServiceImpl(validator, accountTransferAssembler, accountTransferRepository,
                savingsAccountAssembler, savingsAccountDomainService, loanAssembler, loanAccountDomainService,
                savingsAccountWritePlatformService, accountTransferDetailRepository, loanReadPlatformService, gsimRepository,
                configurationDomainService, externalIdFactory, fineractProperties);
    }

    @Test
    void createSavingsToSavingsUsesLightweightSavingsLoading() {
        JsonCommand command = mockSavingsToSavingsCommand();
        SavingsAccount fromSavings = mockSavings();
        SavingsAccount toSavings = mockSavings();
        stubSavingsCurrency(fromSavings, "USD");
        stubSavingsCurrency(toSavings, "USD");
        when(fromSavings.isWithdrawalFeeApplicableForTransfer()).thenReturn(false);
        SavingsAccountTransaction withdrawal = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        SavingsAccountTransaction deposit = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        AccountTransferDetails transferDetails = mockTransferDetails(91L);
        when(withdrawal.getId()).thenReturn(501L);
        when(deposit.getId()).thenReturn(502L);

        when(savingsAccountAssembler.assembleFromLightweight(11L)).thenReturn(fromSavings);
        when(savingsAccountAssembler.assembleFromLightweight(12L)).thenReturn(toSavings);
        when(savingsAccountDomainService.handleWithdrawal(eq(fromSavings), any(), any(), any(), eq(null), any(), eq(false)))
                .thenReturn(withdrawal);
        when(savingsAccountDomainService.handleDeposit(eq(toSavings), any(), any(), any(), eq(null), eq(true), eq(true), eq(false)))
                .thenReturn(deposit);
        when(accountTransferAssembler.assembleSavingsToSavingsTransfer(command, fromSavings, toSavings, withdrawal, deposit))
                .thenReturn(transferDetails);

        var result = underTest.create(command);

        assertEquals(Map.of("fromSavingsTransactionId", 501L, "toSavingsTransactionId", 502L), result.getChanges());
        verify(accountTransferDetailRepository).saveAndFlush(transferDetails);
        verify(savingsAccountAssembler).assembleFromLightweight(11L);
        verify(savingsAccountAssembler).assembleFromLightweight(12L);
        verify(savingsAccountAssembler, never()).assembleFrom(anyLong(), anyBoolean());
    }

    @Test
    void transferFundsSavingsToSavingsUsesLightweightSavingsLoading() {
        SavingsAccount fromSavings = mockSavings();
        SavingsAccount toSavings = mockSavings();
        when(fromSavings.isWithdrawalFeeApplicableForTransfer()).thenReturn(false);
        SavingsAccountTransaction withdrawal = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        SavingsAccountTransaction deposit = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        AccountTransferDetails transferDetails = mockTransferDetails(92L);
        AccountTransferDTO dto = new AccountTransferDTO(LocalDate.of(2026, 1, 10), new BigDecimal("25"), PortfolioAccountType.SAVINGS,
                PortfolioAccountType.SAVINGS, 11L, 12L, "transfer", Locale.US, DateTimeFormatter.ISO_LOCAL_DATE, null, null, null, null,
                null, AccountTransferType.ACCOUNT_TRANSFER.getValue(), null, null, ExternalId.empty(), null, null, null, true, false);

        when(savingsAccountAssembler.assembleFromLightweight(11L)).thenReturn(fromSavings);
        when(savingsAccountAssembler.assembleFromLightweight(12L)).thenReturn(toSavings);
        when(savingsAccountDomainService.handleWithdrawal(eq(fromSavings), any(), any(), any(), eq(null), any(), eq(false)))
                .thenReturn(withdrawal);
        when(savingsAccountDomainService.handleDeposit(eq(toSavings), any(), any(), any(), eq(null), eq(true), eq(true), eq(false)))
                .thenReturn(deposit);
        when(accountTransferAssembler.assembleSavingsToSavingsTransfer(dto, fromSavings, toSavings, withdrawal, deposit))
                .thenReturn(transferDetails);

        org.junit.jupiter.api.Assertions.assertEquals(92L, underTest.transferFunds(dto));
        verify(savingsAccountAssembler).assembleFromLightweight(11L);
        verify(savingsAccountAssembler).assembleFromLightweight(12L);
        verify(savingsAccountAssembler, never()).assembleFrom(anyLong(), anyBoolean());
    }

    @Test
    void refundByTransferUsesLightweightSavingsLoading() {
        JsonCommand command = org.mockito.Mockito.mock(JsonCommand.class);
        Loan loan = org.mockito.Mockito.mock(Loan.class);
        SavingsAccount toSavings = mockSavings();
        LoanTransaction loanRefund = org.mockito.Mockito.mock(LoanTransaction.class);
        SavingsAccountTransaction deposit = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        AccountTransferDetails transferDetails = mockTransferDetails(93L);
        when(command.localDateValueOfParameterNamed(transferDateParamName)).thenReturn(LocalDate.of(2026, 1, 10));
        when(command.bigDecimalValueOfParameterNamed(transferAmountParamName)).thenReturn(new BigDecimal("25"));
        when(command.extractLocale()).thenReturn(Locale.US);
        when(command.dateFormat()).thenReturn("dd MMMM yyyy");
        when(command.longValueOfParameterNamed(fromAccountIdParamName)).thenReturn(21L);
        when(command.longValueOfParameterNamed(toAccountIdParamName)).thenReturn(12L);
        when(loanAssembler.assembleFrom(21L)).thenReturn(loan);
        when(loanReadPlatformService.retrieveTotalPaidInAdvance(21L)).thenReturn(new PaidInAdvanceData(new BigDecimal("100")));
        when(externalIdFactory.create()).thenReturn(ExternalId.empty());
        when(loanAccountDomainService.makeRefundForActiveLoan(eq(21L), any(CommandProcessingResultBuilder.class), any(), any(), eq(null),
                eq(null), any())).thenReturn(loanRefund);
        when(savingsAccountAssembler.assembleFromLightweight(12L)).thenReturn(toSavings);
        when(savingsAccountDomainService.handleDeposit(eq(toSavings), any(), any(), any(), eq(null), eq(true), eq(true), eq(false)))
                .thenReturn(deposit);
        when(accountTransferAssembler.assembleLoanToSavingsTransfer(command, loan, toSavings, deposit, loanRefund))
                .thenReturn(transferDetails);

        underTest.refundByTransfer(command);

        verify(accountTransferDetailRepository).saveAndFlush(transferDetails);
        verify(savingsAccountAssembler).assembleFromLightweight(12L);
        verify(savingsAccountAssembler, never()).assembleFrom(anyLong(), anyBoolean());
    }

    @Test
    void reverseAllTransactionsBatchesLoanTransferReversals() {
        AccountTransferTransaction fromTransfer = org.mockito.Mockito.mock(AccountTransferTransaction.class);
        AccountTransferTransaction toTransfer = org.mockito.Mockito.mock(AccountTransferTransaction.class);
        LoanTransaction fromLoanTransaction = org.mockito.Mockito.mock(LoanTransaction.class);
        LoanTransaction toLoanTransaction = org.mockito.Mockito.mock(LoanTransaction.class);
        SavingsAccountTransaction fromSavingsTransaction = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        SavingsAccountTransaction toSavingsTransaction = org.mockito.Mockito.mock(SavingsAccountTransaction.class);
        AccountTransferDetails fromDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        AccountTransferDetails toDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        SavingsAccount fromSavings = mockSavings();
        SavingsAccount toSavings = mockSavings();

        when(accountTransferRepository.findAllByLoanId(55L)).thenReturn(List.of(fromTransfer, toTransfer));
        when(fromTransfer.getFromLoanTransaction()).thenReturn(fromLoanTransaction);
        when(fromTransfer.getFromTransaction()).thenReturn(fromSavingsTransaction);
        when(fromTransfer.accountTransferDetails()).thenReturn(fromDetails);
        when(fromDetails.fromSavingsAccount()).thenReturn(fromSavings);
        when(fromLoanTransaction.getId()).thenReturn(41L);
        when(fromSavingsTransaction.getId()).thenReturn(61L);
        when(fromSavings.getId()).thenReturn(71L);
        when(toTransfer.getToLoanTransaction()).thenReturn(toLoanTransaction);
        when(toTransfer.getToSavingsTransaction()).thenReturn(toSavingsTransaction);
        when(toTransfer.accountTransferDetails()).thenReturn(toDetails);
        when(toDetails.toSavingsAccount()).thenReturn(toSavings);
        when(toLoanTransaction.getId()).thenReturn(42L);
        when(toSavingsTransaction.getId()).thenReturn(62L);
        when(toSavings.getId()).thenReturn(72L);

        underTest.reverseAllTransactions(55L, PortfolioAccountType.LOAN);

        verify(loanAccountDomainService).reverseTransfers(argThat(transactions -> transactions.size() == 2
                && transactions.contains(fromLoanTransaction) && transactions.contains(toLoanTransaction)));
        verify(loanAccountDomainService, never()).reverseTransfer(any());
        verify(savingsAccountWritePlatformService).undoTransaction(71L, 61L, true);
        verify(savingsAccountWritePlatformService).undoTransaction(72L, 62L, true);
        verify(fromTransfer).reverse();
        verify(toTransfer).reverse();
        verify(accountTransferRepository).saveAll(List.of(fromTransfer, toTransfer));
    }

    private JsonCommand mockSavingsToSavingsCommand() {
        JsonCommand command = org.mockito.Mockito.mock(JsonCommand.class);
        when(command.localDateValueOfParameterNamed(transferDateParamName)).thenReturn(LocalDate.of(2026, 1, 10));
        when(command.bigDecimalValueOfParameterNamed(transferAmountParamName)).thenReturn(new BigDecimal("25"));
        when(command.extractLocale()).thenReturn(Locale.US);
        when(command.dateFormat()).thenReturn("dd MMMM yyyy");
        when(command.integerValueSansLocaleOfParameterNamed(fromAccountTypeParamName)).thenReturn(PortfolioAccountType.SAVINGS.getValue());
        when(command.integerValueSansLocaleOfParameterNamed(toAccountTypeParamName)).thenReturn(PortfolioAccountType.SAVINGS.getValue());
        when(command.longValueOfParameterNamed(fromAccountIdParamName)).thenReturn(11L);
        when(command.longValueOfParameterNamed(toAccountIdParamName)).thenReturn(12L);
        return command;
    }

    private SavingsAccount mockSavings() {
        return org.mockito.Mockito.mock(SavingsAccount.class);
    }

    private void stubSavingsCurrency(SavingsAccount savings, String currencyCode) {
        MonetaryCurrency currency = org.mockito.Mockito.mock(MonetaryCurrency.class);
        when(currency.getCode()).thenReturn(currencyCode);
        when(savings.getCurrency()).thenReturn(currency);
    }

    private AccountTransferDetails mockTransferDetails(Long id) {
        AccountTransferDetails transferDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        when(transferDetails.getId()).thenReturn(id);
        return transferDetails;
    }
}
