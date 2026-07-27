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
package org.apache.fineract.accounting.journalentry.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForSavings;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.data.ChargePaymentDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsJournalEntryAllocation;
import org.apache.fineract.accounting.journalentry.data.SavingsTransactionDTO;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeCommissionAllocationDTO;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CashBasedAccountingProcessorForSavings implements AccountingProcessorForSavings {

    private final AccountingProcessorHelper helper;
    private final NipSwitchAccountingConfigurationProvider nipSwitchAccountingConfigurationProvider;

    @Override
    public void createJournalEntriesForSavings(final SavingsDTO savingsDTO) {
        try (AccountingProcessorHelper.JournalEntryProcessingBatch ignored = this.helper.startJournalEntryProcessingBatch()) {
            final Long savingsProductId = savingsDTO.getSavingsProductId();
            final Long savingsId = savingsDTO.getSavingsId();
            final String currencyCode = savingsDTO.getCurrencyCode();
            final List<JournalEntry> journalEntries = new ArrayList<>();

            for (final SavingsTransactionDTO savingsTransactionDTO : savingsDTO.getNewSavingsTransactions()) {
                final LocalDate transactionDate = savingsTransactionDTO.getTransactionDate();
                final String transactionId = savingsTransactionDTO.getTransactionId();
                final Office office = savingsDTO.getOffice() != null ? savingsDTO.getOffice()
                        : this.helper.getOfficeById(savingsTransactionDTO.getOfficeId());
                final Long paymentTypeId = savingsTransactionDTO.getPaymentTypeId();
                final boolean isReversal = savingsTransactionDTO.isReversed();
                final BigDecimal amount = savingsTransactionDTO.getAmount();
                final BigDecimal overdraftAmount = savingsTransactionDTO.getOverdraftAmount();
                final List<ChargePaymentDTO> feePayments = savingsTransactionDTO.getFeePayments();
                final List<ChargePaymentDTO> penaltyPayments = savingsTransactionDTO.getPenaltyPayments();

                this.helper.checkForBranchClosures(savingsDTO.getOfficeId(), transactionDate);

                if (savingsTransactionDTO.getTransactionType().isDeposit() && StringUtils.isNotBlank(savingsTransactionDTO.getSwitchId())) {
                    createNipDepositJournalEntries(savingsProductId, savingsId, currencyCode, journalEntries, savingsTransactionDTO,
                            transactionDate, transactionId, office, paymentTypeId, isReversal, amount, overdraftAmount);
                } else if (savingsTransactionDTO.getTransactionType().isWithdrawal()
                        && StringUtils.isNotBlank(savingsTransactionDTO.getSwitchId())) {
                    createNipPrincipalJournalEntries(savingsProductId, savingsId, currencyCode, journalEntries, savingsTransactionDTO,
                            transactionDate, transactionId, office, paymentTypeId, isReversal, amount, overdraftAmount);
                } else if (savingsTransactionDTO.getTransactionType().isCommission()
                        && StringUtils.isNotBlank(savingsTransactionDTO.getSwitchId())) {
                    createCommissionJournalEntries(savingsProductId, savingsId, currencyCode, journalEntries, savingsTransactionDTO,
                            transactionDate, transactionId, office, paymentTypeId, isReversal, amount, overdraftAmount);
                } else if (savingsTransactionDTO.getTransactionType().isVat()
                        && StringUtils.isNotBlank(savingsTransactionDTO.getSwitchId())) {
                    createVatJournalEntries(savingsProductId, savingsId, currencyCode, journalEntries, transactionDate, transactionId,
                            office, paymentTypeId, isReversal, amount, overdraftAmount);
                } else if (savingsTransactionDTO.getTransactionType().isWithdrawal() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(),
                                FinancialActivity.LIABILITY_TRANSFER.getValue(), savingsProductId, paymentTypeId, savingsId, transactionId,
                                transactionDate, overdraftAmount, isReversal, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    CashAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.LIABILITY_TRANSFER.getValue(),
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal, journalEntries);
                        }
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(),
                                CashAccountsForSavings.SAVINGS_REFERENCE.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, overdraftAmount, isReversal, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    CashAccountsForSavings.SAVINGS_CONTROL.getValue(), CashAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal, journalEntries);
                        }
                    }
                } else if (savingsTransactionDTO.getTransactionType().isDeposit() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                FinancialActivity.LIABILITY_TRANSFER.getValue(),
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, overdraftAmount, isReversal, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    FinancialActivity.LIABILITY_TRANSFER.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal, journalEntries);
                        }
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, overdraftAmount, isReversal, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    CashAccountsForSavings.SAVINGS_REFERENCE.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal, journalEntries);
                        }
                    }
                } else if (savingsTransactionDTO.getTransactionType().isDeposit()) {
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                FinancialActivity.LIABILITY_TRANSFER.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                                journalEntries);
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_REFERENCE.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                                journalEntries);
                    }
                } else if (savingsTransactionDTO.getTransactionType().isDividendPayout()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            FinancialActivity.PAYABLE_DIVIDENDS.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isWithdrawal()) {
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.LIABILITY_TRANSFER.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                                journalEntries);
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_CONTROL.getValue(), CashAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                                journalEntries);
                    }
                } else if (savingsTransactionDTO.getTransactionType().isEscheat()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.SAVINGS_CONTROL.getValue(), CashAccountsForSavings.ESCHEAT_LIABILITY.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isEmtLevy() && savingsTransactionDTO.isOverdraftTransaction()) {
                    // EMT Levy on an overdraft: split between the overdrawn portion (DR overdraft control)
                    // and the remainder against regular savings, CR the EMT Levy liability for the full amount.
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, overdraftAmount, isReversal,
                            journalEntries);
                    if (isPositive) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(), savingsProductId,
                                paymentTypeId, savingsId, transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal,
                                journalEntries);
                    }
                } else if (savingsTransactionDTO.getTransactionType().isEmtLevy()) {
                    // AB-265 EMT Levy: DR Savings Control, CR EMT Levy liability (via FinancialActivity mapping).
                    // The amount was computed in Synapse and bundled into this transaction via referenceTransactions.
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(), savingsProductId,
                            paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isInterestPosting()
                        && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    if (savingsTransactionDTO.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.INTEREST_ON_SAVINGS.getValue(),
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, overdraftAmount, isReversal, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    CashAccountsForSavings.INTEREST_ON_SAVINGS.getValue(),
                                    CashAccountsForSavings.SAVINGS_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal, journalEntries);
                        }
                    }
                } else if (savingsTransactionDTO.getTransactionType().isInterestPosting()) {
                    if (savingsTransactionDTO.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                CashAccountsForSavings.INTEREST_ON_SAVINGS.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                                journalEntries);
                    }
                } else if (savingsTransactionDTO.getTransactionType().isWithholdTax()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavingsTax(office, currencyCode,
                            CashAccountsForSavings.SAVINGS_CONTROL, CashAccountsForSavings.SAVINGS_REFERENCE, savingsProductId,
                            paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                            savingsTransactionDTO.getTaxPayments(), journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isFeeDeduction() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    if (penaltyPayments.size() > 0) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL, CashAccountsForSavings.INCOME_FROM_PENALTIES,
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, overdraftAmount, isReversal,
                                penaltyPayments, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                    CashAccountsForSavings.SAVINGS_CONTROL, CashAccountsForSavings.INCOME_FROM_PENALTIES, savingsProductId,
                                    paymentTypeId, savingsId, transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal,
                                    penaltyPayments, journalEntries);
                        }
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL, CashAccountsForSavings.INCOME_FROM_FEES,
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, overdraftAmount, isReversal,
                                feePayments, journalEntries);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                    CashAccountsForSavings.SAVINGS_CONTROL, CashAccountsForSavings.INCOME_FROM_FEES, savingsProductId,
                                    paymentTypeId, savingsId, transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal,
                                    feePayments, journalEntries);
                        }
                    }
                } else if (savingsTransactionDTO.getTransactionType().isFeeDeduction()) {
                    if (penaltyPayments.size() > 0) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_CONTROL, CashAccountsForSavings.INCOME_FROM_PENALTIES, savingsProductId,
                                paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, penaltyPayments,
                                journalEntries);
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                CashAccountsForSavings.SAVINGS_CONTROL, CashAccountsForSavings.INCOME_FROM_FEES, savingsProductId,
                                paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, feePayments, journalEntries);
                    }
                } else if (savingsTransactionDTO.getTransactionType().isInitiateTransfer()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.SAVINGS_CONTROL.getValue(), CashAccountsForSavings.TRANSFERS_SUSPENSE.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isWithdrawTransfer()
                        || savingsTransactionDTO.getTransactionType().isApproveTransfer()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.TRANSFERS_SUSPENSE.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isOverdraftInterest()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.SAVINGS_REFERENCE.getValue(), CashAccountsForSavings.INCOME_FROM_INTEREST.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isWrittenoff()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            CashAccountsForSavings.LOSSES_WRITTEN_OFF.getValue(),
                            CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                            transactionId, transactionDate, amount, isReversal, journalEntries);
                } else if (savingsTransactionDTO.getTransactionType().isOverdraftFee()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                            CashAccountsForSavings.SAVINGS_REFERENCE, CashAccountsForSavings.INCOME_FROM_FEES, savingsProductId,
                            paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, feePayments, journalEntries);
                }
            }

            if (!journalEntries.isEmpty()) {
                this.helper.persistJournalEntries(journalEntries);
            }
        }
    }

    private void createNipPrincipalJournalEntries(final Long savingsProductId, final Long savingsId, final String currencyCode,
            final List<JournalEntry> journalEntries, final SavingsTransactionDTO savingsTransactionDTO, final LocalDate transactionDate,
            final String transactionId, final Office office, final Long paymentTypeId, final boolean isReversal, final BigDecimal amount,
            final BigDecimal overdraftAmount) {
        final NipSwitchAccountingConfigurationProvider.OutboundConfiguration configuration = this.nipSwitchAccountingConfigurationProvider
                .requireOutbound(savingsTransactionDTO.getSwitchId());
        final List<SavingsJournalEntryAllocation> debitAllocations = createCustomerControlAllocations(savingsProductId, paymentTypeId,
                amount, overdraftAmount);
        this.helper.createBalancedJournalEntriesForSavings(office, currencyCode, savingsId, transactionId, transactionDate,
                debitAllocations, List.of(new SavingsJournalEntryAllocation(configuration.switchPayableGlAccountId(), amount)), isReversal,
                journalEntries);
    }

    private void createNipDepositJournalEntries(final Long savingsProductId, final Long savingsId, final String currencyCode,
            final List<JournalEntry> journalEntries, final SavingsTransactionDTO savingsTransactionDTO, final LocalDate transactionDate,
            final String transactionId, final Office office, final Long paymentTypeId, final boolean isReversal, final BigDecimal amount,
            final BigDecimal overdraftAmount) {
        final NipSwitchAccountingConfigurationProvider.InboundConfiguration configuration = this.nipSwitchAccountingConfigurationProvider
                .requireInbound(savingsTransactionDTO.getSwitchId());
        final List<SavingsJournalEntryAllocation> creditAllocations = createCustomerControlAllocations(savingsProductId, paymentTypeId,
                amount, overdraftAmount);
        this.helper.createBalancedJournalEntriesForSavings(office, currencyCode, savingsId, transactionId, transactionDate,
                List.of(new SavingsJournalEntryAllocation(configuration.switchReceivableGlAccountId(), amount)), creditAllocations,
                isReversal, journalEntries);
    }

    private void createCommissionJournalEntries(final Long savingsProductId, final Long savingsId, final String currencyCode,
            final List<JournalEntry> journalEntries, final SavingsTransactionDTO savingsTransactionDTO, final LocalDate transactionDate,
            final String transactionId, final Office office, final Long paymentTypeId, final boolean isReversal, final BigDecimal amount,
            final BigDecimal overdraftAmount) {
        final SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation = requireBalancedCommissionAllocation(
                savingsTransactionDTO, amount);
        final NipSwitchAccountingConfigurationProvider.OutboundConfiguration configuration = this.nipSwitchAccountingConfigurationProvider
                .requireOutbound(savingsTransactionDTO.getSwitchId());
        final List<SavingsJournalEntryAllocation> creditAllocations = new ArrayList<>(2);
        if (commissionAllocation.switchFeeAmount().signum() > 0) {
            creditAllocations
                    .add(new SavingsJournalEntryAllocation(configuration.switchFeeGlAccountId(), commissionAllocation.switchFeeAmount()));
        }
        if (commissionAllocation.bankCommissionAmount().signum() > 0) {
            creditAllocations.add(new SavingsJournalEntryAllocation(configuration.commissionIncomeGlAccountId(),
                    commissionAllocation.bankCommissionAmount()));
        }
        final List<SavingsJournalEntryAllocation> debitAllocations = createCustomerControlAllocations(savingsProductId, paymentTypeId,
                amount, overdraftAmount);
        this.helper.createBalancedJournalEntriesForSavings(office, currencyCode, savingsId, transactionId, transactionDate,
                debitAllocations, creditAllocations, isReversal, journalEntries);
    }

    private SavingsAccountingBridgeCommissionAllocationDTO requireBalancedCommissionAllocation(
            final SavingsTransactionDTO savingsTransactionDTO, final BigDecimal amount) {
        final SavingsAccountingBridgeCommissionAllocationDTO allocation = savingsTransactionDTO.getCommissionAllocation();
        if (allocation == null || allocation.switchFeeAmount() == null || allocation.bankCommissionAmount() == null) {
            throw new PlatformDataIntegrityException("error.msg.savings.commission.accounting.allocation.required",
                    "Commission accounting requires both supplied allocation legs");
        }
        if (allocation.switchFeeAmount().signum() < 0 || allocation.bankCommissionAmount().signum() < 0) {
            throw new PlatformDataIntegrityException("error.msg.savings.commission.accounting.allocation.not.negative",
                    "Commission accounting allocation amounts must be non-negative");
        }
        if (allocation.switchFeeAmount().add(allocation.bankCommissionAmount()).compareTo(amount) != 0) {
            throw new PlatformDataIntegrityException("error.msg.savings.commission.accounting.allocation.sum.mismatch",
                    "Commission accounting allocation amounts must equal the Commission transaction amount", amount,
                    allocation.switchFeeAmount(), allocation.bankCommissionAmount());
        }
        return allocation;
    }

    private void createVatJournalEntries(final Long savingsProductId, final Long savingsId, final String currencyCode,
            final List<JournalEntry> journalEntries, final LocalDate transactionDate, final String transactionId, final Office office,
            final Long paymentTypeId, final boolean isReversal, final BigDecimal amount, final BigDecimal overdraftAmount) {
        final GLAccount vatPayableAccount = this.helper.getLinkedGLAccountForSavingsProduct(savingsProductId,
                FinancialActivity.VAT_PAYABLE.getValue(), paymentTypeId);
        final List<SavingsJournalEntryAllocation> debitAllocations = createCustomerControlAllocations(savingsProductId, paymentTypeId,
                amount, overdraftAmount);
        this.helper.createBalancedJournalEntriesForSavings(office, currencyCode, savingsId, transactionId, transactionDate,
                debitAllocations, List.of(new SavingsJournalEntryAllocation(vatPayableAccount.getId(), amount)), isReversal,
                journalEntries);
    }

    private List<SavingsJournalEntryAllocation> createCustomerControlAllocations(final Long savingsProductId, final Long paymentTypeId,
            final BigDecimal amount, final BigDecimal overdraftAmount) {
        final BigDecimal effectiveOverdraftAmount = overdraftAmount == null ? BigDecimal.ZERO : overdraftAmount;
        final BigDecimal customerFundedAmount = amount.subtract(effectiveOverdraftAmount);
        final List<SavingsJournalEntryAllocation> debitAllocations = new ArrayList<>(2);
        if (customerFundedAmount.signum() > 0) {
            final GLAccount savingsControlAccount = this.helper.getLinkedGLAccountForSavingsProduct(savingsProductId,
                    CashAccountsForSavings.SAVINGS_CONTROL.getValue(), paymentTypeId);
            debitAllocations.add(new SavingsJournalEntryAllocation(savingsControlAccount.getId(), customerFundedAmount));
        }
        if (effectiveOverdraftAmount.signum() > 0) {
            final GLAccount overdraftPortfolioControlAccount = this.helper.getLinkedGLAccountForSavingsProduct(savingsProductId,
                    CashAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), paymentTypeId);
            debitAllocations.add(new SavingsJournalEntryAllocation(overdraftPortfolioControlAccount.getId(), effectiveOverdraftAmount));
        }
        return debitAllocations;
    }
}
