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
import org.apache.fineract.accounting.aggregatoraccounting.domain.AggregatorAccountingConfigurationProvider;
import org.apache.fineract.accounting.common.AccountingConstants.AccrualAccountsForSavings;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.data.ChargePaymentDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsJournalEntryAllocation;
import org.apache.fineract.accounting.journalentry.data.SavingsTransactionDTO;
import org.apache.fineract.accounting.nipswitch.domain.NipSwitchAccountingConfigurationProvider;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeCommissionAllocationDTO;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccrualBasedAccountingProcessorForSavings implements AccountingProcessorForSavings {

    private final AccountingProcessorHelper helper;
    private final NipSwitchAccountingConfigurationProvider nipSwitchAccountingConfigurationProvider;
    private final AggregatorAccountingConfigurationProvider aggregatorAccountingConfigurationProvider;

    @Override
    public void createJournalEntriesForSavings(final SavingsDTO savingsDTO) {
        try (AccountingProcessorHelper.JournalEntryProcessingBatch ignored = this.helper.startJournalEntryProcessingBatch()) {
            final Long savingsProductId = savingsDTO.getSavingsProductId();
            final Long savingsId = savingsDTO.getSavingsId();
            final String currencyCode = savingsDTO.getCurrencyCode();
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

                final NipAccountingContext nipAccountingContext = new NipAccountingContext(savingsProductId, savingsId, currencyCode,
                        savingsTransactionDTO, office);
                if (tryCreateNipJournalEntries(nipAccountingContext)) {
                    continue;
                }
                if (tryCreateBillsPostingJournalEntries(nipAccountingContext)) {
                    continue;
                }

                if (savingsTransactionDTO.getTransactionType().isWithdrawal() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(),
                                FinancialActivity.LIABILITY_TRANSFER.getValue(), savingsProductId, paymentTypeId, savingsId, transactionId,
                                transactionDate, overdraftAmount, isReversal);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.LIABILITY_TRANSFER.getValue(),
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal);
                        }
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(),
                                AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, overdraftAmount, isReversal);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                    AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal);
                        }
                    }
                }

                else if (savingsTransactionDTO.getTransactionType().isDeposit() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                FinancialActivity.LIABILITY_TRANSFER.getValue(),
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId,
                                savingsId, transactionId, transactionDate, overdraftAmount, isReversal);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    FinancialActivity.LIABILITY_TRANSFER.getValue(), AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal);
                        }
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId,
                                savingsId, transactionId, transactionDate, overdraftAmount, isReversal);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                                    AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal);
                        }
                    }
                }

                /** Handle Deposits and reversals of deposits **/
                else if (savingsTransactionDTO.getTransactionType().isDeposit()) {
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                FinancialActivity.LIABILITY_TRANSFER.getValue(), AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                                AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, amount, isReversal);
                    }
                }

                /** Handle Deposits and reversals of Dividend pay outs **/
                else if (savingsTransactionDTO.getTransactionType().isDividendPayout()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            FinancialActivity.PAYABLE_DIVIDENDS.getValue(), AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                }

                /** Handle withdrawals and reversals of withdrawals **/
                else if (savingsTransactionDTO.getTransactionType().isWithdrawal()) {
                    if (savingsTransactionDTO.isAccountTransfer()) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.LIABILITY_TRANSFER.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                    } else {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(), savingsProductId, paymentTypeId, savingsId,
                                transactionId, transactionDate, amount, isReversal);
                    }
                }

                /** AB-339: DR Savings Control, CR Signed E-Statement Fee income (via FinancialActivity mapping). */
                else if (savingsTransactionDTO.getTransactionType().isSignedStatementFee()) {
                    createSignedStatementFeeJournalEntries(nipAccountingContext);
                }

                /**
                 * AB-339: VAT also rides standalone (no switchId) alongside the signed e-statement fee — DR Savings
                 * Control, CR VAT Payable (via FinancialActivity mapping). tryCreateNipJournalEntries only handles the
                 * switch-scoped NIP-transfer VAT leg, so a switchless VAT reference falls through to here.
                 */
                else if (savingsTransactionDTO.getTransactionType().isVat()) {
                    createVatJournalEntries(nipAccountingContext);
                }

                /** AB-510: the bills-only Convenience Fee leg, resolved by aggregatorCode. */
                else if (savingsTransactionDTO.getTransactionType().isConvenienceFee()) {
                    createAggregatorConvenienceFeeJournalEntries(nipAccountingContext);
                }

                else if (savingsTransactionDTO.getTransactionType().isEscheat()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), AccrualAccountsForSavings.ESCHEAT_LIABILITY.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                }
                /**
                 * AB-265 EMT Levy on overdraft: split DR between OVERDRAFT_PORTFOLIO_CONTROL and SAVINGS_CONTROL,
                 * single CR via FinancialActivity.EMT_LEVY (liability).
                 */
                else if (savingsTransactionDTO.getTransactionType().isEmtLevy() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, overdraftAmount, isReversal);
                    if (isPositive) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                amount.subtract(overdraftAmount), isReversal);
                    }
                }
                /**
                 * AB-265 EMT Levy: DR Savings Control, CR EMT Levy liability (via FinancialActivity mapping). The
                 * amount was computed in Synapse and bundled into this transaction via referenceTransactions.
                 */
                else if (savingsTransactionDTO.getTransactionType().isEmtLevy()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), FinancialActivity.EMT_LEVY.getValue(), savingsProductId,
                            paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                }
                /**
                 * Handle Interest Applications and reversals of Interest Applications
                 **/
                else if (savingsTransactionDTO.getTransactionType().isInterestPosting() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    // Post journal entry if earned interest amount is greater than
                    // zero
                    if (savingsTransactionDTO.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.INTEREST_ON_SAVINGS.getValue(),
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId,
                                savingsId, transactionId, transactionDate, overdraftAmount, isReversal);
                        if (isPositive) {
                            this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.INTEREST_ON_SAVINGS.getValue(),
                                    AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal);
                        }
                    }
                }

                else if (savingsTransactionDTO.getTransactionType().isInterestPosting()) {
                    // Post journal entry if earned interest amount is greater than
                    // zero
                    if (savingsTransactionDTO.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                                AccrualAccountsForSavings.INTEREST_PAYABLE.getValue(), AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                    }
                }

                else if (savingsTransactionDTO.getTransactionType().isAccrual()) {
                    // Post journal entry for Accrual Recognition
                    if (savingsTransactionDTO.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        if (MathUtil.isGreaterThanZero(overdraftAmount)) {
                            this.helper.createAccrualBasedDebitJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.INTEREST_ON_SAVINGS.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount, isReversal);
                            this.helper.createAccrualBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.INTEREST_PAYABLE.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount, isReversal);
                        } else {
                            this.helper.createAccrualBasedDebitJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.INTEREST_RECEIVABLE.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount, isReversal);
                            this.helper.createAccrualBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode,
                                    AccrualAccountsForSavings.INCOME_FROM_INTEREST.getValue(), savingsProductId, paymentTypeId, savingsId,
                                    transactionId, transactionDate, amount, isReversal);
                        }
                    }
                }

                else if (savingsTransactionDTO.getTransactionType().isWithholdTax()) {
                    this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsTax(office, currencyCode,
                            AccrualAccountsForSavings.SAVINGS_CONTROL, AccrualAccountsForSavings.SAVINGS_REFERENCE, savingsProductId,
                            paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                            savingsTransactionDTO.getTaxPayments());
                }

                /** Handle Fees Deductions and reversals of Fees Deductions **/
                else if (savingsTransactionDTO.getTransactionType().isFeeDeduction() && savingsTransactionDTO.isOverdraftTransaction()) {
                    boolean isPositive = amount.subtract(overdraftAmount).compareTo(BigDecimal.ZERO) > 0;
                    // Is the Charge a penalty?
                    if (penaltyPayments.size() > 0) {
                        this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL, AccrualAccountsForSavings.INCOME_FROM_PENALTIES,
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, overdraftAmount, isReversal,
                                penaltyPayments);
                        if (isPositive) {
                            this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                    AccrualAccountsForSavings.SAVINGS_CONTROL, AccrualAccountsForSavings.INCOME_FROM_PENALTIES,
                                    savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate,
                                    amount.subtract(overdraftAmount), isReversal, penaltyPayments);
                        }
                    } else {
                        this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL, AccrualAccountsForSavings.INCOME_FROM_FEES,
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, overdraftAmount, isReversal,
                                feePayments);
                        if (isPositive) {
                            this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                    AccrualAccountsForSavings.SAVINGS_CONTROL, AccrualAccountsForSavings.INCOME_FROM_FEES, savingsProductId,
                                    paymentTypeId, savingsId, transactionId, transactionDate, amount.subtract(overdraftAmount), isReversal,
                                    feePayments);
                        }
                    }
                }

                else if (savingsTransactionDTO.getTransactionType().isFeeDeduction()) {
                    // Is the Charge a penalty?
                    if (penaltyPayments.size() > 0) {
                        this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_CONTROL, AccrualAccountsForSavings.INCOME_FROM_PENALTIES,
                                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal,
                                penaltyPayments);
                    } else {
                        this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                                AccrualAccountsForSavings.SAVINGS_CONTROL, AccrualAccountsForSavings.INCOME_FROM_FEES, savingsProductId,
                                paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, feePayments);
                    }
                }

                /** Handle Transfers proposal **/
                else if (savingsTransactionDTO.getTransactionType().isInitiateTransfer()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), AccrualAccountsForSavings.TRANSFERS_SUSPENSE.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                }

                /** Handle Transfer Withdrawal or Acceptance **/
                else if (savingsTransactionDTO.getTransactionType().isWithdrawTransfer()
                        || savingsTransactionDTO.getTransactionType().isApproveTransfer()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.TRANSFERS_SUSPENSE.getValue(), AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
                }

                /** overdraft **/
                else if (savingsTransactionDTO.getTransactionType().isOverdraftInterest()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.SAVINGS_REFERENCE.getValue(),
                            AccrualAccountsForSavings.INCOME_FROM_INTEREST.getValue(), savingsProductId, paymentTypeId, savingsId,
                            transactionId, transactionDate, amount, isReversal);
                } else if (savingsTransactionDTO.getTransactionType().isWrittenoff()) {
                    this.helper.createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode,
                            AccrualAccountsForSavings.LOSSES_WRITTEN_OFF.getValue(),
                            AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), savingsProductId, paymentTypeId, savingsId,
                            transactionId, transactionDate, amount, isReversal);
                } else if (savingsTransactionDTO.getTransactionType().isOverdraftFee()) {
                    this.helper.createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode,
                            AccrualAccountsForSavings.SAVINGS_REFERENCE, AccrualAccountsForSavings.INCOME_FROM_FEES, savingsProductId,
                            paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, feePayments);
                }
            }
        }
    }

    private boolean tryCreateNipJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        if (StringUtils.isBlank(transaction.getSwitchId())) {
            return false;
        }
        if (transaction.getTransactionType().isDeposit()) {
            createNipDepositJournalEntries(context);
        } else if (transaction.getTransactionType().isWithdrawal()) {
            createNipPrincipalJournalEntries(context);
        } else if (transaction.getTransactionType().isCommission()) {
            createCommissionJournalEntries(context);
        } else if (transaction.getTransactionType().isVat()) {
            createVatJournalEntries(context);
        } else {
            return false;
        }
        return true;
    }

    private void createNipPrincipalJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final NipSwitchAccountingConfigurationProvider.OutboundConfiguration configuration = this.nipSwitchAccountingConfigurationProvider
                .requireOutbound(transaction.getSwitchId());
        createBalancedJournalEntries(context, createCustomerControlAllocations(context),
                List.of(new SavingsJournalEntryAllocation(configuration.switchPayableGlAccountId(), transaction.getAmount())));
    }

    private void createNipDepositJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final NipSwitchAccountingConfigurationProvider.InboundConfiguration configuration = this.nipSwitchAccountingConfigurationProvider
                .requireInbound(transaction.getSwitchId());
        createBalancedJournalEntries(context,
                List.of(new SavingsJournalEntryAllocation(configuration.switchReceivableGlAccountId(), transaction.getAmount())),
                createCustomerControlAllocations(context));
    }

    private void createCommissionJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final SavingsAccountingBridgeCommissionAllocationDTO commissionAllocation = requireBalancedCommissionAllocation(transaction);
        final NipSwitchAccountingConfigurationProvider.OutboundConfiguration configuration = this.nipSwitchAccountingConfigurationProvider
                .requireOutbound(transaction.getSwitchId());
        final List<SavingsJournalEntryAllocation> creditAllocations = new ArrayList<>(2);
        if (commissionAllocation.switchFeeAmount().signum() > 0) {
            creditAllocations
                    .add(new SavingsJournalEntryAllocation(configuration.switchFeeGlAccountId(), commissionAllocation.switchFeeAmount()));
        }
        if (commissionAllocation.bankCommissionAmount().signum() > 0) {
            creditAllocations.add(new SavingsJournalEntryAllocation(configuration.commissionIncomeGlAccountId(),
                    commissionAllocation.bankCommissionAmount()));
        }
        createBalancedJournalEntries(context, createCustomerControlAllocations(context), creditAllocations);
    }

    private SavingsAccountingBridgeCommissionAllocationDTO requireBalancedCommissionAllocation(final SavingsTransactionDTO transaction) {
        final SavingsAccountingBridgeCommissionAllocationDTO allocation = transaction.getCommissionAllocation();
        if (allocation == null || allocation.switchFeeAmount() == null || allocation.bankCommissionAmount() == null) {
            throw new PlatformDataIntegrityException("error.msg.savings.commission.accounting.allocation.required",
                    "Commission accounting requires both supplied allocation legs");
        }
        if (allocation.switchFeeAmount().signum() < 0 || allocation.bankCommissionAmount().signum() < 0) {
            throw new PlatformDataIntegrityException("error.msg.savings.commission.accounting.allocation.not.negative",
                    "Commission accounting allocation amounts must be non-negative");
        }
        if (allocation.switchFeeAmount().add(allocation.bankCommissionAmount()).compareTo(transaction.getAmount()) != 0) {
            throw new PlatformDataIntegrityException("error.msg.savings.commission.accounting.allocation.sum.mismatch",
                    "Commission accounting allocation amounts must equal the Commission transaction amount", transaction.getAmount(),
                    allocation.switchFeeAmount(), allocation.bankCommissionAmount());
        }
        return allocation;
    }

    private void createVatJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final GLAccount vatPayableAccount = this.helper.getLinkedGLAccountForSavingsProduct(context.savingsProductId(),
                FinancialActivity.VAT_PAYABLE.getValue(), transaction.getPaymentTypeId());
        createBalancedJournalEntries(context, createCustomerControlAllocations(context),
                List.of(new SavingsJournalEntryAllocation(vatPayableAccount.getId(), transaction.getAmount())));
    }

    private void createSignedStatementFeeJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final GLAccount signedStatementFeeIncomeAccount = this.helper.getLinkedGLAccountForSavingsProduct(context.savingsProductId(),
                FinancialActivity.SIGNED_STATEMENT_FEE_INCOME.getValue(), transaction.getPaymentTypeId());
        createBalancedJournalEntries(context, createCustomerControlAllocations(context),
                List.of(new SavingsJournalEntryAllocation(signedStatementFeeIncomeAccount.getId(), transaction.getAmount())));
    }

    /**
     * AB-510: mirrors {@link #tryCreateNipJournalEntries}'s early-intercept shape — a bills/airtime withdrawal carries
     * {@code aggregatorCode} instead of {@code switchId} on the same WITHDRAWAL transaction type, so it must be
     * intercepted here too, before the generic {@code isWithdrawal()} branch further down would otherwise credit the
     * plain savings-reference account.
     */
    private boolean tryCreateBillsPostingJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        if (StringUtils.isBlank(transaction.getAggregatorCode()) || !transaction.getTransactionType().isWithdrawal()) {
            return false;
        }
        createBillsPostingPrincipalJournalEntries(context);
        return true;
    }

    /**
     * AB-510: the bills/airtime withdrawal's principal leg splits the single customer debit into two credits — the
     * amount owed to the aggregator (bill amount minus the bank's commission) and the bank's own commission income —
     * resolved by {@code aggregatorCode} via {@link AggregatorAccountingConfigurationProvider}, mirroring
     * {@link #createCommissionJournalEntries}'s multi-credit-allocation shape. Unlike a NIP transfer, bills posting
     * never sends a separate reference-transaction leg for the commission — it rides on the primary withdrawal's own
     * {@code aggregatorCommissionAmount} field instead.
     */
    private void createBillsPostingPrincipalJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final AggregatorAccountingConfigurationProvider.Configuration configuration = this.aggregatorAccountingConfigurationProvider
                .requireConfiguration(transaction.getAggregatorCode());
        final BigDecimal commissionAmount = transaction.getAggregatorCommissionAmount() == null ? BigDecimal.ZERO
                : transaction.getAggregatorCommissionAmount();
        final BigDecimal aggregatorPayableAmount = transaction.getAmount().subtract(commissionAmount);
        final List<SavingsJournalEntryAllocation> creditAllocations = new ArrayList<>(2);
        if (aggregatorPayableAmount.signum() > 0) {
            creditAllocations.add(new SavingsJournalEntryAllocation(configuration.aggregatorPayableGlAccountId(), aggregatorPayableAmount));
        }
        if (commissionAmount.signum() > 0) {
            creditAllocations.add(new SavingsJournalEntryAllocation(configuration.commissionIncomeGlAccountId(), commissionAmount));
        }
        createBalancedJournalEntries(context, createCustomerControlAllocations(context), creditAllocations);
    }

    /**
     * AB-510: the bills-only Convenience Fee leg (never posted for airtime/data). Falls back to the aggregator's
     * commission income account when no dedicated convenience-fee account is configured.
     */
    private void createAggregatorConvenienceFeeJournalEntries(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final AggregatorAccountingConfigurationProvider.Configuration configuration = this.aggregatorAccountingConfigurationProvider
                .requireConfiguration(transaction.getAggregatorCode());
        createBalancedJournalEntries(context, createCustomerControlAllocations(context), List
                .of(new SavingsJournalEntryAllocation(configuration.resolvedConvenienceFeeIncomeGlAccountId(), transaction.getAmount())));
    }

    private List<SavingsJournalEntryAllocation> createCustomerControlAllocations(final NipAccountingContext context) {
        final SavingsTransactionDTO transaction = context.transaction();
        final BigDecimal effectiveOverdraftAmount = transaction.getOverdraftAmount() == null ? BigDecimal.ZERO
                : transaction.getOverdraftAmount();
        final BigDecimal customerFundedAmount = transaction.getAmount().subtract(effectiveOverdraftAmount);
        final List<SavingsJournalEntryAllocation> debitAllocations = new ArrayList<>(2);
        if (customerFundedAmount.signum() > 0) {
            final GLAccount savingsControlAccount = this.helper.getLinkedGLAccountForSavingsProduct(context.savingsProductId(),
                    AccrualAccountsForSavings.SAVINGS_CONTROL.getValue(), transaction.getPaymentTypeId());
            debitAllocations.add(new SavingsJournalEntryAllocation(savingsControlAccount.getId(), customerFundedAmount));
        }
        if (effectiveOverdraftAmount.signum() > 0) {
            final GLAccount overdraftPortfolioControlAccount = this.helper.getLinkedGLAccountForSavingsProduct(context.savingsProductId(),
                    AccrualAccountsForSavings.OVERDRAFT_PORTFOLIO_CONTROL.getValue(), transaction.getPaymentTypeId());
            debitAllocations.add(new SavingsJournalEntryAllocation(overdraftPortfolioControlAccount.getId(), effectiveOverdraftAmount));
        }
        return debitAllocations;
    }

    private void createBalancedJournalEntries(final NipAccountingContext context,
            final List<SavingsJournalEntryAllocation> debitAllocations, final List<SavingsJournalEntryAllocation> creditAllocations) {
        final SavingsTransactionDTO transaction = context.transaction();
        this.helper.createBalancedJournalEntriesForSavings(context.office(), context.currencyCode(), context.savingsId(),
                transaction.getTransactionId(), transaction.getTransactionDate(), debitAllocations, creditAllocations,
                transaction.isReversed());
    }

    private record NipAccountingContext(Long savingsProductId, Long savingsId, String currencyCode, SavingsTransactionDTO transaction,
            Office office) {
    }
}
