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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.accounting.common.AccountingConstants;
import org.apache.fineract.accounting.common.AccountingConstants.AccrualAccountsForLoan;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForLoan;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForSavings;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForShares;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.common.AccountingConstants.LoanProductAccountingParams;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccount;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.journalentry.data.ChargePaymentDTO;
import org.apache.fineract.accounting.journalentry.data.ClientChargePaymentDTO;
import org.apache.fineract.accounting.journalentry.data.ClientTransactionDTO;
import org.apache.fineract.accounting.journalentry.data.LoanDTO;
import org.apache.fineract.accounting.journalentry.data.LoanTransactionDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsDTO;
import org.apache.fineract.accounting.journalentry.data.SavingsJournalEntryAllocation;
import org.apache.fineract.accounting.journalentry.data.SavingsTransactionDTO;
import org.apache.fineract.accounting.journalentry.data.SharesDTO;
import org.apache.fineract.accounting.journalentry.data.SharesTransactionDTO;
import org.apache.fineract.accounting.journalentry.data.TaxPaymentDTO;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.accounting.journalentry.exception.JournalEntryInvalidException;
import org.apache.fineract.accounting.journalentry.exception.JournalEntryInvalidException.GlJournalEntryInvalidReason;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.accounting.producttoaccountmapping.exception.ProductToGLAccountMappingNotFoundException;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.event.business.domain.journalentry.LoanJournalEntryCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.data.AccountingBridgeLoanTransactionDTO;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargeData;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargePaidByDTO;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeChargePaymentDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTaxDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeTransactionDTO;
import org.apache.fineract.portfolio.shareaccounts.data.ShareAccountTransactionEnumData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;

@RequiredArgsConstructor
public class AccountingProcessorHelper {

    public static final String LOAN_TRANSACTION_IDENTIFIER = "L";
    public static final String SAVINGS_TRANSACTION_IDENTIFIER = "S";
    public static final String CLIENT_TRANSACTION_IDENTIFIER = "C";
    public static final String PROVISIONING_TRANSACTION_IDENTIFIER = "P";
    public static final String SHARE_TRANSACTION_IDENTIFIER = "SH";

    private final JournalEntryRepository glJournalEntryRepository;
    private final ProductToGLAccountMappingRepository accountMappingRepository;
    private final FinancialActivityAccountRepositoryWrapper financialActivityAccountRepository;
    private final GLClosureRepository closureRepository;
    private final GLAccountRepository glAccountRepository;
    private final OfficeRepository officeRepository;
    private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    private final ChargeRepositoryWrapper chargeRepositoryWrapper;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final ThreadLocal<JournalEntryProcessingBatchCache> journalEntryProcessingBatchCache = new ThreadLocal<>();
    private AccountingProcessorHelper self = this;

    @Autowired
    public void setSelf(@Lazy AccountingProcessorHelper self) {
        this.self = self;
    }

    public JournalEntryProcessingBatch startJournalEntryProcessingBatch() {
        JournalEntryProcessingBatchCache cache = this.journalEntryProcessingBatchCache.get();
        if (cache == null) {
            cache = new JournalEntryProcessingBatchCache();
            this.journalEntryProcessingBatchCache.set(cache);
        }
        cache.incrementDepth();
        return new JournalEntryProcessingBatch();
    }

    public final class JournalEntryProcessingBatch implements AutoCloseable {

        private boolean closed;

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            final JournalEntryProcessingBatchCache cache = journalEntryProcessingBatchCache.get();
            if (cache != null && cache.decrementDepth() == 0) {
                journalEntryProcessingBatchCache.remove();
            }
            this.closed = true;
        }
    }

    public LoanDTO populateLoanDtoFromDTO(
            final org.apache.fineract.portfolio.loanaccount.data.AccountingBridgeDataDTO accountingBridgeData) {
        final Long loanId = accountingBridgeData.getLoanId();
        final Long loanProductId = accountingBridgeData.getLoanProductId();
        final Long officeId = accountingBridgeData.getOfficeId();
        final String currencyCode = accountingBridgeData.getCurrencyCode();
        final List<LoanTransactionDTO> newLoanTransactions = new ArrayList<>();
        boolean isAccountTransfer = accountingBridgeData.isAccountTransfer();
        boolean isLoanMarkedAsChargeOff = accountingBridgeData.isChargeOff();
        boolean isLoanMarkedAsFraud = accountingBridgeData.isFraud();
        final Long chargeOffReasonCodeValue = accountingBridgeData.getChargeOffReasonCodeValue();
        final boolean isLoanMarkedAsWrittenOff = accountingBridgeData.isWrittenOff();
        final boolean cashBasedAccountingEnabled = accountingBridgeData.isCashBasedAccountingEnabled();
        final boolean upfrontAccrualBasedAccountingEnabled = accountingBridgeData.isUpfrontAccrualBasedAccountingEnabled();
        final boolean periodicAccrualBasedAccountingEnabled = accountingBridgeData.isPeriodicAccrualBasedAccountingEnabled();
        final boolean merchantBuyDownFee = accountingBridgeData.isMerchantBuyDownFee();

        final List<AccountingBridgeLoanTransactionDTO> loanTransactionDTOs = accountingBridgeData.getNewLoanTransactions();

        for (final AccountingBridgeLoanTransactionDTO loanTxnDto : loanTransactionDTOs) {
            final Long transactionOfficeId = loanTxnDto.getOfficeId();
            final String transactionId = loanTxnDto.getId().toString();
            final LocalDate transactionDate = loanTxnDto.getDate();
            final LoanTransactionEnumData transactionType = loanTxnDto.getType();
            final BigDecimal amount = loanTxnDto.getAmount();
            final BigDecimal principal = loanTxnDto.getPrincipalPortion();
            final BigDecimal interest = loanTxnDto.getInterestPortion();
            final BigDecimal fees = loanTxnDto.getFeeChargesPortion();
            final BigDecimal penalties = loanTxnDto.getPenaltyChargesPortion();
            final BigDecimal overPayments = loanTxnDto.getOverPaymentPortion();
            final boolean reversed = loanTxnDto.isReversed();
            final Long paymentTypeId = loanTxnDto.getPaymentTypeId();
            final String chargeRefundChargeType = loanTxnDto.getChargeRefundChargeType();
            final LoanChargeData loanChargeData = loanTxnDto.getLoanChargeData();

            final List<ChargePaymentDTO> feePaymentDetails = new ArrayList<>();
            final List<ChargePaymentDTO> penaltyPaymentDetails = new ArrayList<>();
            // extract charge payment details (if exists)
            if (loanTxnDto.getLoanChargesPaid() != null) {
                List<LoanChargePaidByDTO> loanChargesPaidData = loanTxnDto.getLoanChargesPaid();
                for (final LoanChargePaidByDTO loanChargePaid : loanChargesPaidData) {
                    final Long chargeId = loanChargePaid.getChargeId();
                    final Long loanChargeId = loanChargePaid.getLoanChargeId();
                    final boolean isPenalty = loanChargePaid.getIsPenalty();
                    final BigDecimal chargeAmountPaid = loanChargePaid.getAmount();
                    final ChargePaymentDTO chargePaymentDTO = new ChargePaymentDTO(chargeId, chargeAmountPaid, loanChargeId);
                    if (isPenalty) {
                        penaltyPaymentDetails.add(chargePaymentDTO);
                    } else {
                        feePaymentDetails.add(chargePaymentDTO);
                    }
                }
            }

            boolean localIsAccountTransfer = isAccountTransfer;
            if (!localIsAccountTransfer) {
                localIsAccountTransfer = this.accountTransfersReadPlatformService.isAccountTransfer(Long.parseLong(transactionId),
                        PortfolioAccountType.LOAN);
            }

            BigDecimal principalPaid = loanTxnDto.getPrincipalPaid();
            BigDecimal feePaid = loanTxnDto.getFeePaid();
            BigDecimal penaltyPaid = loanTxnDto.getPenaltyPaid();

            final LoanTransactionDTO transaction = new LoanTransactionDTO(transactionOfficeId, paymentTypeId, transactionId,
                    transactionDate, transactionType, amount, principal, interest, fees, penalties, overPayments, reversed,
                    penaltyPaymentDetails, feePaymentDetails, localIsAccountTransfer, chargeRefundChargeType, loanChargeData, principalPaid,
                    feePaid, penaltyPaid);

            transaction.setLoanToLoanTransfer(loanTxnDto.isLoanToLoanTransfer());
            newLoanTransactions.add(transaction);
        }

        return new LoanDTO(loanId, loanProductId, officeId, currencyCode, cashBasedAccountingEnabled, upfrontAccrualBasedAccountingEnabled,
                periodicAccrualBasedAccountingEnabled, newLoanTransactions, isLoanMarkedAsChargeOff, isLoanMarkedAsFraud,
                chargeOffReasonCodeValue, isLoanMarkedAsWrittenOff, merchantBuyDownFee,
                accountingBridgeData.getBuydownFeeClassificationCodeValue(),
                accountingBridgeData.getCapitalizedIncomeClassificationCodeValue(), accountingBridgeData.getWriteOffReasonCodeValue());
    }

    public ProductToGLAccountMapping getChargeOffMappingByCodeValue(Long loanProductId, PortfolioProductType productType,
            Long chargeOffReasonId) {
        return getCachedProductToGLAccountMapping(
                ProductToGLAccountMappingCacheKey.chargeOffReason(loanProductId, productType.getValue(), chargeOffReasonId),
                () -> accountMappingRepository.findChargeOffReasonMapping(loanProductId, productType.getValue(), chargeOffReasonId));
    }

    public ProductToGLAccountMapping getWriteOffMappingByCodeValue(Long loanProductId, PortfolioProductType productType,
            Long writeOffReasonId) {
        return getCachedProductToGLAccountMapping(
                ProductToGLAccountMappingCacheKey.writeOffReason(loanProductId, productType.getValue(), writeOffReasonId),
                () -> accountMappingRepository.findWriteOffReasonMapping(loanProductId, productType.getValue(), writeOffReasonId));
    }

    public ProductToGLAccountMapping getClassificationMappingByCodeValue(Long loanProductId, PortfolioProductType productType,
            final Long classificationId, final String classificationType) {
        if (LoanProductAccountingParams.BUYDOWN_FEE_CLASSIFICATION_TO_INCOME_ACCOUNT_MAPPINGS.getValue().equals(classificationType)) {
            return getCachedProductToGLAccountMapping(
                    ProductToGLAccountMappingCacheKey.buydownFeeClassification(loanProductId, productType.getValue(), classificationId),
                    () -> accountMappingRepository.findBuydownFeeClassificationMapping(loanProductId, productType.getValue(),
                            classificationId));
        } else {
            return getCachedProductToGLAccountMapping(
                    ProductToGLAccountMappingCacheKey.capitalizedIncomeClassification(loanProductId, productType.getValue(),
                            classificationId),
                    () -> accountMappingRepository.findCapitalizedIncomeClassificationMapping(loanProductId, productType.getValue(),
                            classificationId));
        }
    }

    private ProductToGLAccountMapping getCachedProductToGLAccountMapping(ProductToGLAccountMappingCacheKey cacheKey,
            Supplier<ProductToGLAccountMapping> lookup) {
        final JournalEntryProcessingBatchCache cache = this.journalEntryProcessingBatchCache.get();
        if (cache == null) {
            return lookup.get();
        }
        if (cache.contains(cacheKey)) {
            return cache.get(cacheKey);
        }
        final ProductToGLAccountMapping accountMapping = lookup.get();
        cache.put(cacheKey, accountMapping);
        return accountMapping;
    }

    private ProductToGLAccountMapping findCoreProductToFinAccountMapping(final Long productId, final int productType,
            final int financialAccountType) {
        return getCachedProductToGLAccountMapping(ProductToGLAccountMappingCacheKey.core(productId, productType, financialAccountType),
                () -> this.accountMappingRepository.findCoreProductToFinAccountMapping(productId, productType, financialAccountType));
    }

    private ProductToGLAccountMapping findPaymentTypeMapping(final Long productId, final int productType, final int financialAccountType,
            final Long paymentTypeId) {
        return getCachedProductToGLAccountMapping(
                ProductToGLAccountMappingCacheKey.paymentType(productId, productType, financialAccountType, paymentTypeId),
                () -> this.accountMappingRepository.findByProductIdAndProductTypeAndFinancialAccountTypeAndPaymentTypeId(productId,
                        productType, financialAccountType, paymentTypeId));
    }

    private ProductToGLAccountMapping findChargeMapping(final Long productId, final int productType, final int financialAccountType,
            final Long chargeId) {
        return getCachedProductToGLAccountMapping(
                ProductToGLAccountMappingCacheKey.charge(productId, productType, financialAccountType, chargeId),
                () -> this.accountMappingRepository.findProductIdAndProductTypeAndFinancialAccountTypeAndChargeId(productId, productType,
                        financialAccountType, chargeId));
    }

    public SavingsDTO populateSavingsDtoFromDTO(final SavingsAccountingBridgeDTO accountingBridgeData) {
        return populateSavingsDtoFromDTO(accountingBridgeData, getOfficeById(accountingBridgeData.getOfficeId()));
    }

    public SavingsDTO populateSavingsDtoFromDTO(final SavingsAccountingBridgeDTO accountingBridgeData, final Office office) {
        final Long loanId = accountingBridgeData.getSavingsId();
        final Long loanProductId = accountingBridgeData.getSavingsProductId();
        final Long officeId = accountingBridgeData.getOfficeId();
        final String currencyCode = accountingBridgeData.getCurrencyCode();
        final List<SavingsTransactionDTO> newSavingsTransactions = new ArrayList<>();
        boolean isAccountTransfer = accountingBridgeData.isAccountTransfer();
        final boolean cashBasedAccountingEnabled = accountingBridgeData.isCashBasedAccountingEnabled();
        final boolean accrualBasedAccountingEnabled = accountingBridgeData.isAccrualBasedAccountingEnabled();

        final List<SavingsAccountingBridgeTransactionDTO> newTransactionsMap = accountingBridgeData.getNewSavingsTransactions();

        for (final SavingsAccountingBridgeTransactionDTO map : newTransactionsMap) {
            final Long transactionOfficeId = map.getOfficeId();
            final String transactionId = map.getId().toString();
            final LocalDate transactionDate = map.getDate();
            final SavingsAccountTransactionEnumData transactionType = map.getType();
            final BigDecimal amount = map.getAmount();
            final boolean reversed = map.isReversed();
            final Long paymentTypeId = map.getPaymentTypeId();
            final BigDecimal overdraftAmount = map.getOverdraftAmount();

            final List<ChargePaymentDTO> feePayments = new ArrayList<>();
            final List<ChargePaymentDTO> penaltyPayments = new ArrayList<>();
            for (final SavingsAccountingBridgeChargePaymentDTO loanChargePaid : map.getSavingsChargesPaid()) {
                final Long chargeId = loanChargePaid.getChargeId();
                final Long loanChargeId = loanChargePaid.getSavingsChargeId();
                final boolean isPenalty = loanChargePaid.isPenalty();
                final BigDecimal chargeAmountPaid = loanChargePaid.getAmount();
                final ChargePaymentDTO chargePaymentDTO = new ChargePaymentDTO(chargeId, chargeAmountPaid, loanChargeId);
                if (isPenalty) {
                    penaltyPayments.add(chargePaymentDTO);
                } else {
                    feePayments.add(chargePaymentDTO);
                }
            }

            final List<TaxPaymentDTO> taxPayments = new ArrayList<>();
            for (final SavingsAccountingBridgeTaxDTO taxData : map.getTaxDetails()) {
                final BigDecimal taxAmount = taxData.getAmount();
                final Long creditAccountId = taxData.getCreditAccountId();
                final Long debitAccountId = taxData.getDebitAccountId();
                taxPayments.add(new TaxPaymentDTO(debitAccountId, creditAccountId, taxAmount));
            }

            if (!isAccountTransfer) {
                isAccountTransfer = this.accountTransfersReadPlatformService.isAccountTransfer(Long.parseLong(transactionId),
                        PortfolioAccountType.SAVINGS);
            }
            final SavingsTransactionDTO transaction = new SavingsTransactionDTO(transactionOfficeId, paymentTypeId, transactionId,
                    transactionDate, transactionType, amount, reversed, feePayments, penaltyPayments, overdraftAmount, isAccountTransfer,
                    taxPayments, map.getSwitchId(), map.getCommissionAllocation());

            newSavingsTransactions.add(transaction);

        }

        return new SavingsDTO(loanId, loanProductId, officeId, currencyCode, cashBasedAccountingEnabled, accrualBasedAccountingEnabled,
                newSavingsTransactions, office);
    }

    public SharesDTO populateSharesDtoFromMap(final Map<String, Object> accountingBridgeData, final boolean cashBasedAccountingEnabled,
            final boolean accrualBasedAccountingEnabled) {
        final Long shareAccountId = (Long) accountingBridgeData.get("shareAccountId");
        final Long shareProductId = (Long) accountingBridgeData.get("shareProductId");
        final Long officeId = (Long) accountingBridgeData.get("officeId");
        final String currencyCode = (String) accountingBridgeData.get("currencyCode");
        final List<SharesTransactionDTO> newTransactions = new ArrayList<>();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> newTransactionsMap = (List<Map<String, Object>>) accountingBridgeData.get("newTransactions");

        for (final Map<String, Object> map : newTransactionsMap) {
            final Long transactionOfficeId = (Long) map.get("officeId");
            final String transactionId = ((Long) map.get("id")).toString();
            final LocalDate transactionDate = ((LocalDate) map.get("date"));
            final ShareAccountTransactionEnumData transactionType = (ShareAccountTransactionEnumData) map.get("type");
            final ShareAccountTransactionEnumData transactionStatus = (ShareAccountTransactionEnumData) map.get("status");
            final BigDecimal amount = (BigDecimal) map.get("amount");
            final BigDecimal chargeAmount = (BigDecimal) map.get("chargeAmount");
            final Long paymentTypeId = (Long) map.get("paymentTypeId");

            final List<ChargePaymentDTO> feePayments = new ArrayList<>();
            // extract charge payment details (if exists)
            if (map.containsKey("chargesPaid")) {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> chargesPaidData = (List<Map<String, Object>>) map.get("chargesPaid");
                for (final Map<String, Object> chargePaid : chargesPaidData) {
                    final Long chargeId = (Long) chargePaid.get("chargeId");
                    final Long loanChargeId = (Long) chargePaid.get("sharesChargeId");
                    final BigDecimal chargeAmountPaid = (BigDecimal) chargePaid.get("amount");
                    final ChargePaymentDTO chargePaymentDTO = new ChargePaymentDTO(chargeId, chargeAmountPaid, loanChargeId);
                    feePayments.add(chargePaymentDTO);
                }
            }
            final SharesTransactionDTO transaction = new SharesTransactionDTO(transactionOfficeId, paymentTypeId, transactionId,
                    transactionDate, transactionType, transactionStatus, amount, chargeAmount, feePayments);

            newTransactions.add(transaction);

        }

        return new SharesDTO(shareAccountId, shareProductId, officeId, currencyCode, cashBasedAccountingEnabled,
                accrualBasedAccountingEnabled, newTransactions);
    }

    public ClientTransactionDTO populateClientTransactionDtoFromMap(final Map<String, Object> accountingBridgeData) {

        final Long transactionOfficeId = (Long) accountingBridgeData.get("officeId");
        final Long clientId = (Long) accountingBridgeData.get("clientId");
        final Long transactionId = (Long) accountingBridgeData.get("id");
        final LocalDate transactionDate = ((LocalDate) accountingBridgeData.get("date"));
        final EnumOptionData transactionType = (EnumOptionData) accountingBridgeData.get("type");
        final BigDecimal amount = (BigDecimal) accountingBridgeData.get("amount");
        final boolean reversed = (Boolean) accountingBridgeData.get("reversed");
        final Long paymentTypeId = (Long) accountingBridgeData.get("paymentTypeId");
        final String currencyCode = (String) accountingBridgeData.get("currencyCode");
        final Boolean accountingEnabled = (Boolean) accountingBridgeData.get("accountingEnabled");

        final List<ClientChargePaymentDTO> clientChargePaymentDTOs = new ArrayList<>();
        // extract client charge payment details (if exists)
        if (accountingBridgeData.containsKey("clientChargesPaid")) {
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> clientChargesPaidData = (List<Map<String, Object>>) accountingBridgeData
                    .get("clientChargesPaid");
            for (final Map<String, Object> clientChargePaid : clientChargesPaidData) {
                final Long chargeId = (Long) clientChargePaid.get("chargeId");
                final Long clientChargeId = (Long) clientChargePaid.get("clientChargeId");
                final boolean isPenalty = (Boolean) clientChargePaid.get("isPenalty");
                final BigDecimal chargeAmountPaid = (BigDecimal) clientChargePaid.get("amount");
                final Long incomeAccountId = (Long) clientChargePaid.get("incomeAccountId");
                final ClientChargePaymentDTO clientChargePaymentDTO = new ClientChargePaymentDTO(chargeId, chargeAmountPaid, clientChargeId,
                        isPenalty, incomeAccountId);
                clientChargePaymentDTOs.add(clientChargePaymentDTO);
            }
        }

        return new ClientTransactionDTO(clientId, transactionOfficeId, paymentTypeId, transactionId, transactionDate, transactionType,
                currencyCode, amount, reversed, accountingEnabled, clientChargePaymentDTOs);

    }

    /**
     * Convenience method that creates a pair of related Debits and Credits for Accrual Based accounting.
     *
     * The target accounts for debits and credits are switched in case of a reversal
     *
     * @param office
     *            office
     * @param currencyCode
     *            currencyCode
     * @param accountTypeToBeDebited
     *            Enum of the placeholder GLAccount to be debited
     * @param accountTypeToBeCredited
     *            Enum of the placeholder of the GLAccount to be credited
     * @param loanProductId
     *            loanProductId
     * @param loanId
     *            loanId
     * @param transactionId
     *            transactionId
     * @param transactionDate
     *            transactionDate
     * @param totalAmount
     *            totalAmount
     * @param chargePaymentDTOs
     *            chargePaymentDTOs
     */
    public void createJournalEntriesForLoanCharges(final Office office, final String currencyCode, final Integer accountTypeToBeDebited,
            final Integer accountTypeToBeCredited, final Long loanProductId, final Long loanId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal totalAmount, final List<ChargePaymentDTO> chargePaymentDTOs) {

        final Map<GLAccount, BigDecimal> creditDetailsMap = new LinkedHashMap<>();
        final Map<GLAccount, BigDecimal> debitDetailsMap = new LinkedHashMap<>();

        for (final ChargePaymentDTO chargePaymentDTO : chargePaymentDTOs) {
            final Long chargeId = chargePaymentDTO.getChargeId();
            final GLAccount chargeSpecificCreditAccount = getLinkedGLAccountForLoanCharges(loanProductId, accountTypeToBeCredited,
                    chargeId);
            final GLAccount chargeSpecificDebitAccount = getLinkedGLAccountForLoanCharges(loanProductId, accountTypeToBeDebited, chargeId);
            final BigDecimal chargeSpecificAmount = chargePaymentDTO.getAmount();

            // aggregate amounts by account for credit entries
            creditDetailsMap.merge(chargeSpecificCreditAccount, chargeSpecificAmount, BigDecimal::add);

            // aggregate amounts by account for debit entries
            debitDetailsMap.merge(chargeSpecificDebitAccount, chargeSpecificAmount, BigDecimal::add);
        }

        BigDecimal totalCreditedAmount = BigDecimal.ZERO;
        BigDecimal totalDebitedAmount = BigDecimal.ZERO;

        // Create credit journal entries
        for (final Map.Entry<GLAccount, BigDecimal> entry : creditDetailsMap.entrySet()) {
            final GLAccount account = entry.getKey();
            final BigDecimal amount = entry.getValue();
            totalCreditedAmount = totalCreditedAmount.add(amount);
            createCreditJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
        }

        // Create debit journal entries using charge-specific debit accounts
        for (final Map.Entry<GLAccount, BigDecimal> entry : debitDetailsMap.entrySet()) {
            final GLAccount account = entry.getKey();
            final BigDecimal amount = entry.getValue();
            totalDebitedAmount = totalDebitedAmount.add(amount);
            createDebitJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
        }

        if (totalAmount.compareTo(totalCreditedAmount) != 0) {
            throw new PlatformDataIntegrityException(
                    "Meltdown in advanced accounting...sum of all charge credits does not equal the total transaction amount",
                    "Sum of charge credits (" + totalCreditedAmount + ") does not equal transaction total (" + totalAmount + ") for loan "
                            + loanId + ", transaction " + transactionId,
                    totalCreditedAmount, totalAmount);
        }

        if (totalAmount.compareTo(totalDebitedAmount) != 0) {
            throw new PlatformDataIntegrityException(
                    "Meltdown in advanced accounting...sum of all charge debits does not equal the total transaction amount",
                    "Sum of charge debits (" + totalDebitedAmount + ") does not equal transaction total (" + totalAmount + ") for loan "
                            + loanId + ", transaction " + transactionId,
                    totalDebitedAmount, totalAmount);
        }
    }

    /**
     * Convenience method that creates a pair of related Debits and Credits for Cash Based accounting.
     *
     * The target accounts for debits and credits are switched in case of a reversal
     *
     * @param office
     * @param accountTypeToBeDebited
     *            Enum of the placeholder GLAccount to be debited
     * @param accountTypeToBeCredited
     *            Enum of the placeholder of the GLAccount to be credited
     * @param savingsProductId
     * @param paymentTypeId
     * @param loanId
     * @param transactionId
     * @param transactionDate
     * @param amount
     * @param isReversal
     */
    public void createCashBasedJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeDebited, final Integer accountTypeToBeCredited, final Long savingsProductId,
            final Long paymentTypeId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount, final Boolean isReversal) {
        final List<JournalEntry> journalEntries = new ArrayList<>();
        createCashBasedJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeDebited, accountTypeToBeCredited,
                savingsProductId, paymentTypeId, loanId, transactionId, transactionDate, amount, isReversal, journalEntries);
        persistJournalEntries(journalEntries);
    }

    public void createCashBasedJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeDebited, final Integer accountTypeToBeCredited, final Long savingsProductId,
            final Long paymentTypeId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount, final Boolean isReversal, final List<JournalEntry> journalEntries) {
        int accountTypeToDebitId = accountTypeToBeDebited;
        int accountTypeToCreditId = accountTypeToBeCredited;
        // reverse debits and credits for reversals
        if (isReversal) {
            accountTypeToDebitId = accountTypeToBeCredited;
            accountTypeToCreditId = accountTypeToBeDebited;
        }
        journalEntries.addAll(createJournalEntriesForSavings(office, currencyCode, accountTypeToDebitId, accountTypeToCreditId,
                savingsProductId, paymentTypeId, loanId, transactionId, transactionDate, amount));
    }

    /**
     * Convenience method that creates a pair of related Debits and Credits for Cash Based accounting.
     *
     * The target accounts for debits and credits are switched in case of a reversal
     *
     * @param office
     * @param accountTypeToBeDebited
     *            Enum of the placeholder GLAccount to be debited
     * @param accountTypeToBeCredited
     *            Enum of the placeholder of the GLAccount to be credited
     * @param loanProductId
     * @param paymentTypeId
     * @param loanId
     * @param transactionId
     * @param transactionDate
     * @param amount
     */
    public void createJournalEntriesForLoan(final Office office, final String currencyCode, final Integer accountTypeToBeDebited,
            final Integer accountTypeToBeCredited, final Long loanProductId, final Long paymentTypeId, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        int accountTypeToDebitId = accountTypeToBeDebited;
        int accountTypeToCreditId = accountTypeToBeCredited;
        createJournalEntriesForLoan(office, currencyCode, accountTypeToDebitId, accountTypeToCreditId, loanProductId, paymentTypeId, loanId,
                transactionId, transactionDate, amount);
    }

    public void createJournalEntriesForLoan(final Office office, final String currencyCode, final Integer accountTypeToBeDebited,
            final GLAccount accountToBeCredited, final Long loanProductId, final Long paymentTypeId, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        int accountTypeToDebitId = accountTypeToBeDebited;
        createJournalEntriesForLoan(office, currencyCode, accountTypeToDebitId, accountToBeCredited, loanProductId, paymentTypeId, loanId,
                transactionId, transactionDate, amount);
    }

    public void createSplitJournalEntriesForLoan(Office office, String currencyCode, List<JournalAmountHolder> splitAccountsHolder,
            JournalAmountHolder totalAccountHolder, Long loanProductId, Long paymentTypeId, Long loanId, String transactionId,
            LocalDate transactionDate) {
        splitAccountsHolder.forEach(journalItemHolder -> {
            if (MathUtil.isGreaterThanZero(journalItemHolder.getAmount())) {
                final GLAccount account = getLinkedGLAccountForLoanProduct(loanProductId, journalItemHolder.getAccountType(),
                        paymentTypeId);
                createDebitJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate,
                        journalItemHolder.getAmount());
            }
        });
        if (MathUtil.isGreaterThanZero(totalAccountHolder.getAmount())) {
            final GLAccount totalAccount = getLinkedGLAccountForLoanProduct(loanProductId, totalAccountHolder.getAccountType(),
                    paymentTypeId);
            createCreditJournalEntryForLoan(office, currencyCode, totalAccount, loanId, transactionId, transactionDate,
                    totalAccountHolder.getAmount());
        }
    }

    public void createCreditJournalEntryForLoan(final Office office, final String currencyCode,
            final CashAccountsForLoan accountMappingType, final Long loanProductId, final Long paymentTypeId, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final int accountMappingTypeId = accountMappingType.getValue();
        createCreditJournalEntryForLoan(office, currencyCode, accountMappingTypeId, loanProductId, paymentTypeId, loanId, transactionId,
                transactionDate, amount);
    }

    public void createCreditJournalEntryForLoan(final Office office, final String currencyCode,
            final AccrualAccountsForLoan accountMappingType, final Long loanProductId, final Long paymentTypeId, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final int accountMappingTypeId = accountMappingType.getValue();
        createCreditJournalEntryForLoan(office, currencyCode, accountMappingTypeId, loanProductId, paymentTypeId, loanId, transactionId,
                transactionDate, amount);
    }

    public void checkForBranchClosures(final long officeId, final LocalDate transactionDate) {
        final GLClosure latestGLClosure = self.getLatestClosureByBranch(officeId);
        // check if an accounting closure has happened for this branch after the transaction Date
        if (latestGLClosure != null) {
            if (!DateUtils.isBefore(latestGLClosure.getClosingDate(), transactionDate)) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.ACCOUNTING_CLOSED, latestGLClosure.getClosingDate(),
                        null, null);
            }
        }
    }

    @Cacheable(value = "glClosuresByOfficeId", key = "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil).getTenant().getTenantIdentifier().concat(#officeId)", unless = "#result == null")
    public GLClosure getLatestClosureByBranch(final long officeId) {
        return this.closureRepository.getLatestGLClosureByBranch(officeId);
    }

    private void createJournalEntriesForLoan(final Office office, final String currencyCode, final int accountTypeToDebitId,
            final int accountTypeToCreditId, final Long loanProductId, final Long paymentTypeId, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount debitAccount = getLinkedGLAccountForLoanProduct(loanProductId, accountTypeToDebitId, paymentTypeId);
        final GLAccount creditAccount = getLinkedGLAccountForLoanProduct(loanProductId, accountTypeToCreditId, paymentTypeId);
        createDebitJournalEntryForLoan(office, currencyCode, debitAccount, loanId, transactionId, transactionDate, amount);
        createCreditJournalEntryForLoan(office, currencyCode, creditAccount, loanId, transactionId, transactionDate, amount);
    }

    private void createJournalEntriesForLoan(final Office office, final String currencyCode, final int accountTypeToDebitId,
            final GLAccount creditAccount, final Long loanProductId, final Long paymentTypeId, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount debitAccount = getLinkedGLAccountForLoanProduct(loanProductId, accountTypeToDebitId, paymentTypeId);
        createDebitJournalEntryForLoan(office, currencyCode, debitAccount, loanId, transactionId, transactionDate, amount);
        createCreditJournalEntryForLoan(office, currencyCode, creditAccount, loanId, transactionId, transactionDate, amount);
    }

    private List<JournalEntry> createJournalEntriesForSavings(final Office office, final String currencyCode,
            final int accountTypeToDebitId, final int accountTypeToCreditId, final Long savingsProductId, final Long paymentTypeId,
            final Long savingsId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount debitAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId, accountTypeToDebitId, paymentTypeId);
        final GLAccount creditAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId, accountTypeToCreditId,
                paymentTypeId);
        return List.of(
                buildDebitJournalEntryForSavings(office, currencyCode, debitAccount, savingsId, transactionId, transactionDate, amount),
                buildCreditJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId, transactionDate, amount));
    }

    /**
     * Convenience method that creates a pair of related Debits and Credits for Cash Based accounting.
     *
     * The target accounts for debits and credits are switched in case of a reversal
     *
     * @param office
     * @param currencyCode
     * @param accountTypeToBeDebited
     *            Enum of the placeholder GLAccount to be debited
     * @param accountTypeToBeCredited
     *            Enum of the placeholder of the GLAccount to be credited
     * @param savingsProductId
     * @param paymentTypeId
     * @param savingsId
     * @param transactionId
     * @param transactionDate
     * @param amount
     * @param isReversal
     * @param taxDetails
     */
    public void createCashBasedJournalEntriesAndReversalsForSavingsTax(final Office office, final String currencyCode,
            final CashAccountsForSavings accountTypeToBeDebited, final CashAccountsForSavings accountTypeToBeCredited,
            final Long savingsProductId, final Long paymentTypeId, final Long savingsId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal, final List<TaxPaymentDTO> taxDetails) {

        final List<JournalEntry> journalEntries = new ArrayList<>();
        createCashBasedJournalEntriesAndReversalsForSavingsTax(office, currencyCode, accountTypeToBeDebited, accountTypeToBeCredited,
                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, taxDetails, journalEntries);
        persistJournalEntries(journalEntries);
    }

    public void createCashBasedJournalEntriesAndReversalsForSavingsTax(final Office office, final String currencyCode,
            final CashAccountsForSavings accountTypeToBeDebited, final CashAccountsForSavings accountTypeToBeCredited,
            final Long savingsProductId, final Long paymentTypeId, final Long savingsId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal, final List<TaxPaymentDTO> taxDetails,
            final List<JournalEntry> journalEntries) {

        for (TaxPaymentDTO taxPaymentDTO : taxDetails) {
            if (taxPaymentDTO.getAmount() != null) {
                if (taxPaymentDTO.getCreditAccountId() == null) {
                    createCashBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeCredited.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, taxPaymentDTO.getAmount(),
                            isReversal, journalEntries);
                } else {
                    createCashBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode, taxPaymentDTO.getCreditAccountId(),
                            savingsId, transactionId, transactionDate, taxPaymentDTO.getAmount(), isReversal, journalEntries);
                }
            }
        }
        createCashBasedDebitJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeDebited.getValue(), savingsProductId,
                paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
    }

    public void createAccrualBasedJournalEntriesAndReversalsForSavingsTax(final Office office, final String currencyCode,
            final AccountingConstants.AccrualAccountsForSavings accountTypeToBeDebited,
            final AccountingConstants.AccrualAccountsForSavings accountTypeToBeCredited, final Long savingsProductId,
            final Long paymentTypeId, final Long savingsId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount, final Boolean isReversal, final List<TaxPaymentDTO> taxDetails) {
        for (TaxPaymentDTO taxPaymentDTO : taxDetails) {
            if (taxPaymentDTO.getAmount() != null) {
                if (taxPaymentDTO.getCreditAccountId() == null) {
                    createAccrualBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeCredited.getValue(),
                            savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, taxPaymentDTO.getAmount(),
                            isReversal);
                } else {
                    createAccrualBasedBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode,
                            taxPaymentDTO.getCreditAccountId(), savingsId, transactionId, transactionDate, taxPaymentDTO.getAmount(),
                            isReversal);
                }
            }
        }
        createAccrualBasedDebitJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeDebited.getValue(),
                savingsProductId, paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal);
    }

    public void createCashBasedDebitJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeDebited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal) {
        final List<JournalEntry> journalEntries = new ArrayList<>();
        createCashBasedDebitJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeDebited, savingsProductId,
                paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
        persistJournalEntries(journalEntries);
    }

    public void createCashBasedDebitJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeDebited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal,
            final List<JournalEntry> journalEntries) {
        // reverse debits and credits for reversals
        if (isReversal) {
            journalEntries.add(buildCreditJournalEntryForSavings(office, currencyCode, accountTypeToBeDebited, savingsProductId,
                    paymentTypeId, savingsId, transactionId, transactionDate, amount));
        } else {
            journalEntries.add(buildDebitJournalEntryForSavings(office, currencyCode, accountTypeToBeDebited, savingsProductId,
                    paymentTypeId, savingsId, transactionId, transactionDate, amount));
        }
    }

    public void createCashBasedCreditJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeCredited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal) {
        final List<JournalEntry> journalEntries = new ArrayList<>();
        createCashBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode, accountTypeToBeCredited, savingsProductId,
                paymentTypeId, savingsId, transactionId, transactionDate, amount, isReversal, journalEntries);
        persistJournalEntries(journalEntries);
    }

    public void createCashBasedCreditJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeCredited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal,
            final List<JournalEntry> journalEntries) {
        // reverse debits and credits for reversals
        if (isReversal) {
            journalEntries.add(buildDebitJournalEntryForSavings(office, currencyCode, accountTypeToBeCredited, savingsProductId,
                    paymentTypeId, savingsId, transactionId, transactionDate, amount));
        } else {
            journalEntries.add(buildCreditJournalEntryForSavings(office, currencyCode, accountTypeToBeCredited, savingsProductId,
                    paymentTypeId, savingsId, transactionId, transactionDate, amount));
        }
    }

    public void createCashBasedCreditJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Long creditAccountId, final Long savingsId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount, final Boolean isReversal) {
        final List<JournalEntry> journalEntries = new ArrayList<>();
        createCashBasedCreditJournalEntriesAndReversalsForSavings(office, currencyCode, creditAccountId, savingsId, transactionId,
                transactionDate, amount, isReversal, journalEntries);
        persistJournalEntries(journalEntries);
    }

    public void createCashBasedCreditJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Long creditAccountId, final Long savingsId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount, final Boolean isReversal, final List<JournalEntry> journalEntries) {
        // reverse debits and credits for reversals
        final GLAccount creditAccount = getGLAccountById(creditAccountId);
        if (isReversal) {
            journalEntries.add(buildDebitJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId,
                    transactionDate, amount));
        } else {
            journalEntries.add(buildCreditJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId,
                    transactionDate, amount));
        }
    }

    /**
     * Builds a balanced savings journal from directly configured GL identifiers, then persists the complete set only
     * after every allocation has been validated and every account has been resolved.
     *
     * <p>
     * Zero allocations are omitted. Allocation order and duplicate GL identifiers are preserved so callers can retain
     * distinct semantic legs, such as switch fee and bank Commission.
     */
    public List<JournalEntry> createBalancedJournalEntriesForSavings(final Office office, final String currencyCode, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final List<SavingsJournalEntryAllocation> debitAllocations,
            final List<SavingsJournalEntryAllocation> creditAllocations, final boolean isReversal) {
        final List<JournalEntry> journalEntries = new ArrayList<>();
        createBalancedJournalEntriesForSavings(office, currencyCode, savingsId, transactionId, transactionDate, debitAllocations,
                creditAllocations, isReversal, journalEntries);
        return persistJournalEntries(journalEntries);
    }

    public void createBalancedJournalEntriesForSavings(final Office office, final String currencyCode, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final List<SavingsJournalEntryAllocation> debitAllocations,
            final List<SavingsJournalEntryAllocation> creditAllocations, final boolean isReversal,
            final List<JournalEntry> journalEntries) {
        final List<SavingsJournalEntryAllocation> effectiveDebits = validateAllocations(debitAllocations);
        final List<SavingsJournalEntryAllocation> effectiveCredits = validateAllocations(creditAllocations);
        final BigDecimal debitTotal = totalAllocations(effectiveDebits);
        final BigDecimal creditTotal = totalAllocations(effectiveCredits);
        if (effectiveDebits.isEmpty() || effectiveCredits.isEmpty()) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.NO_DEBITS_OR_CREDITS, null, null, null);
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.DEBIT_CREDIT_SUM_MISMATCH, null, null, null);
        }

        final List<ResolvedSavingsJournalEntryAllocation> resolvedDebits = resolveAllocations(effectiveDebits);
        final List<ResolvedSavingsJournalEntryAllocation> resolvedCredits = resolveAllocations(effectiveCredits);
        final List<JournalEntry> balancedEntries = new ArrayList<>(resolvedDebits.size() + resolvedCredits.size());
        appendSavingsJournalEntries(balancedEntries, resolvedDebits, isReversal ? JournalEntryType.CREDIT : JournalEntryType.DEBIT, office,
                currencyCode, savingsId, transactionId, transactionDate);
        appendSavingsJournalEntries(balancedEntries, resolvedCredits, isReversal ? JournalEntryType.DEBIT : JournalEntryType.CREDIT, office,
                currencyCode, savingsId, transactionId, transactionDate);
        journalEntries.addAll(balancedEntries);
    }

    private List<SavingsJournalEntryAllocation> validateAllocations(final List<SavingsJournalEntryAllocation> allocations) {
        if (allocations == null) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.DEBIT_CREDIT_ACCOUNT_OR_AMOUNT_EMPTY, null, null, null);
        }
        final List<SavingsJournalEntryAllocation> effectiveAllocations = new ArrayList<>(allocations.size());
        for (final SavingsJournalEntryAllocation allocation : allocations) {
            if (allocation == null || allocation.glAccountId() == null || allocation.amount() == null || allocation.amount().signum() < 0) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.DEBIT_CREDIT_ACCOUNT_OR_AMOUNT_EMPTY, null, null, null);
            }
            if (allocation.amount().signum() > 0) {
                effectiveAllocations.add(allocation);
            }
        }
        return effectiveAllocations;
    }

    private BigDecimal totalAllocations(final List<SavingsJournalEntryAllocation> allocations) {
        return allocations.stream().map(SavingsJournalEntryAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<ResolvedSavingsJournalEntryAllocation> resolveAllocations(final List<SavingsJournalEntryAllocation> allocations) {
        return allocations.stream().map(
                allocation -> new ResolvedSavingsJournalEntryAllocation(getGLAccountById(allocation.glAccountId()), allocation.amount()))
                .toList();
    }

    private void appendSavingsJournalEntries(final List<JournalEntry> journalEntries,
            final List<ResolvedSavingsJournalEntryAllocation> allocations, final JournalEntryType type, final Office office,
            final String currencyCode, final Long savingsId, final String transactionId, final LocalDate transactionDate) {
        for (final ResolvedSavingsJournalEntryAllocation allocation : allocations) {
            if (type == JournalEntryType.DEBIT) {
                journalEntries.add(buildDebitJournalEntryForSavings(office, currencyCode, allocation.account(), savingsId, transactionId,
                        transactionDate, allocation.amount()));
            } else {
                journalEntries.add(buildCreditJournalEntryForSavings(office, currencyCode, allocation.account(), savingsId, transactionId,
                        transactionDate, allocation.amount()));
            }
        }
    }

    private record ResolvedSavingsJournalEntryAllocation(GLAccount account, BigDecimal amount) {
    }

    public void createAccrualBasedDebitJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeDebited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal) {
        // reverse debits and credits for reversals
        if (isReversal) {
            createCreditJournalEntriesForSavings(office, currencyCode, accountTypeToBeDebited, savingsProductId, paymentTypeId, savingsId,
                    transactionId, transactionDate, amount);
        } else {
            createDebitJournalEntriesForSavings(office, currencyCode, accountTypeToBeDebited, savingsProductId, paymentTypeId, savingsId,
                    transactionId, transactionDate, amount);
        }
    }

    public void createAccrualBasedCreditJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Integer accountTypeToBeCredited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final Boolean isReversal) {
        // reverse debits and credits for reversals
        if (isReversal) {
            createDebitJournalEntriesForSavings(office, currencyCode, accountTypeToBeCredited, savingsProductId, paymentTypeId, savingsId,
                    transactionId, transactionDate, amount);
        } else {
            createCreditJournalEntriesForSavings(office, currencyCode, accountTypeToBeCredited, savingsProductId, paymentTypeId, savingsId,
                    transactionId, transactionDate, amount);
        }
    }

    public void createAccrualBasedBasedCreditJournalEntriesAndReversalsForSavings(final Office office, final String currencyCode,
            final Long creditAccountId, final Long savingsId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount, final Boolean isReversal) {
        // reverse debits and credits for reversals
        final GLAccount creditAccount = getGLAccountById(creditAccountId);
        if (isReversal) {
            createDebitJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId, transactionDate, amount);
        } else {
            createCreditJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId, transactionDate, amount);
        }
    }

    private void createDebitJournalEntriesForSavings(final Office office, final String currencyCode, final int accountTypeToDebitId,
            final Long savingsProductId, final Long paymentTypeId, final Long savingsId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount debitAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId, accountTypeToDebitId, paymentTypeId);
        createDebitJournalEntryForSavings(office, currencyCode, debitAccount, savingsId, transactionId, transactionDate, amount);
    }

    private void createCreditJournalEntriesForSavings(final Office office, final String currencyCode, final int accountTypeToCreditId,
            final Long savingsProductId, final Long paymentTypeId, final Long savingsId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount creditAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId, accountTypeToCreditId,
                paymentTypeId);
        createCreditJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId, transactionDate, amount);
    }

    public void createDebitJournalEntryForLoan(final Office office, final String currencyCode, final int accountMappingTypeId,
            final Long loanProductId, final Long paymentTypeId, final Long loanId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount account = getLinkedGLAccountForLoanProduct(loanProductId, accountMappingTypeId, paymentTypeId);
        createDebitJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
    }

    public void createDebitJournalEntryForLoan(final Office office, final String currencyCode, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final GLAccount account) {
        createDebitJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
    }

    public void createDebitJournalEntryForLoanCharges(final Office office, final String currencyCode, final int accountMappingTypeId,
            final Long loanProductId, final Long chargeId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal amount) {
        final GLAccount account = getLinkedGLAccountForLoanCharges(loanProductId, accountMappingTypeId, chargeId);
        createDebitJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
    }

    public void createCreditJournalEntryForLoanCharges(final Office office, final String currencyCode, final int accountMappingTypeId,
            final Long loanProductId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal totalAmount, final List<ChargePaymentDTO> chargePaymentDTOs) {
        createJournalEntriesForLoanChargesInternal(office, currencyCode, accountMappingTypeId, loanProductId, loanId, transactionId,
                transactionDate, totalAmount, chargePaymentDTOs, true);
    }

    public void createDebitJournalEntryForLoanCharges(final Office office, final String currencyCode, final int accountMappingTypeId,
            final Long loanProductId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal totalAmount, final List<ChargePaymentDTO> chargePaymentDTOs) {
        createJournalEntriesForLoanChargesInternal(office, currencyCode, accountMappingTypeId, loanProductId, loanId, transactionId,
                transactionDate, totalAmount, chargePaymentDTOs, false);
    }

    /**
     * Convenience method that creates a pair of related Debits and Credits for Cash Based accounting.
     *
     * The target accounts for debits and credits are switched in case of a reversal
     *
     * @param office
     *            office
     * @param currencyCode
     *            currencyCode
     * @param accountTypeToBeDebited
     *            Enum of the placeholder GLAccount to be debited
     * @param accountTypeToBeCredited
     *            Enum of the placeholder of the GLAccount to be credited
     * @param savingsProductId
     *            savingsProductId
     * @param paymentTypeId
     *            paymentTypeId
     * @param loanId
     *            loanId
     * @param transactionId
     *            transactionId
     * @param transactionDate
     *            transactionDate
     * @param totalAmount
     *            totalAmount
     * @param isReversal
     *            isReversal
     * @param chargePaymentDTOs
     *            chargePaymentDTOs
     */
    public void createCashBasedJournalEntriesAndReversalsForSavingsCharges(final Office office, final String currencyCode,
            final CashAccountsForSavings accountTypeToBeDebited, CashAccountsForSavings accountTypeToBeCredited,
            final Long savingsProductId, final Long paymentTypeId, final Long loanId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal totalAmount, final Boolean isReversal,
            final List<ChargePaymentDTO> chargePaymentDTOs) {
        final List<JournalEntry> journalEntries = new ArrayList<>();
        createCashBasedJournalEntriesAndReversalsForSavingsCharges(office, currencyCode, accountTypeToBeDebited, accountTypeToBeCredited,
                savingsProductId, paymentTypeId, loanId, transactionId, transactionDate, totalAmount, isReversal, chargePaymentDTOs,
                journalEntries);
        persistJournalEntries(journalEntries);
    }

    public void createCashBasedJournalEntriesAndReversalsForSavingsCharges(final Office office, final String currencyCode,
            final CashAccountsForSavings accountTypeToBeDebited, CashAccountsForSavings accountTypeToBeCredited,
            final Long savingsProductId, final Long paymentTypeId, final Long loanId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal totalAmount, final Boolean isReversal,
            final List<ChargePaymentDTO> chargePaymentDTOs, final List<JournalEntry> journalEntries) {
        // TODO Vishwas: Remove this validation, as and when appropriate Junit
        // tests are written for accounting
        /**
         * Accounting module currently supports a single charge per transaction, throw an error if this is not the case
         * here so any developers changing the expected portfolio behavior would also take care of modifying the
         * accounting code appropriately
         **/
        if (chargePaymentDTOs.size() != 1) {
            throw new PlatformDataIntegrityException("Recent Portfolio changes w.r.t Charges for Savings have Broken the accounting code",
                    "Recent Portfolio changes w.r.t Charges for Savings have Broken the accounting code");
        }
        ChargePaymentDTO chargePaymentDTO = chargePaymentDTOs.get(0);
        GLAccount chargeSpecificAccount = getLinkedGLAccountForSavingsCharges(savingsProductId, accountTypeToBeCredited.getValue(),
                chargePaymentDTO.getChargeId());

        final GLAccount savingsControlAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId,
                accountTypeToBeDebited.getValue(), paymentTypeId);
        if (isReversal) {
            journalEntries.add(buildDebitJournalEntryForSavings(office, currencyCode, chargeSpecificAccount, loanId, transactionId,
                    transactionDate, totalAmount));
            journalEntries.add(buildCreditJournalEntryForSavings(office, currencyCode, savingsControlAccount, loanId, transactionId,
                    transactionDate, totalAmount));
        } else {
            journalEntries.add(buildDebitJournalEntryForSavings(office, currencyCode, savingsControlAccount, loanId, transactionId,
                    transactionDate, totalAmount));
            journalEntries.add(buildCreditJournalEntryForSavings(office, currencyCode, chargeSpecificAccount, loanId, transactionId,
                    transactionDate, totalAmount));
        }
    }

    public void createAccrualBasedJournalEntriesAndReversalsForSavingsCharges(final Office office, final String currencyCode,
            final AccountingConstants.AccrualAccountsForSavings accountTypeToBeDebited,
            final AccountingConstants.AccrualAccountsForSavings accountTypeToBeCredited, final Long savingsProductId,
            final Long paymentTypeId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal totalAmount, final Boolean isReversal, final List<ChargePaymentDTO> chargePaymentDTOs) {
        // TODO Vishwas: Remove this validation, as and when appropriate Junit
        // tests are written for accounting
        /**
         * Accounting module currently supports a single charge per transaction, throw an error if this is not the case
         * here so any developers changing the expected portfolio behavior would also take care of modifying the
         * accounting code appropriately
         **/
        if (chargePaymentDTOs.size() != 1) {
            throw new PlatformDataIntegrityException("Recent Portfolio changes w.r.t Charges for Savings have Broken the accounting code",
                    "Recent Portfolio changes w.r.t Charges for Savings have Broken the accounting code");
        }
        ChargePaymentDTO chargePaymentDTO = chargePaymentDTOs.get(0);
        GLAccount chargeSpecificAccount = getLinkedGLAccountForSavingsCharges(savingsProductId, accountTypeToBeCredited.getValue(),
                chargePaymentDTO.getChargeId());

        final GLAccount savingsControlAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId,
                accountTypeToBeDebited.getValue(), paymentTypeId);
        if (isReversal) {
            createDebitJournalEntryForSavings(office, currencyCode, chargeSpecificAccount, loanId, transactionId, transactionDate,
                    totalAmount);
            createCreditJournalEntryForSavings(office, currencyCode, savingsControlAccount, loanId, transactionId, transactionDate,
                    totalAmount);
        } else {
            createDebitJournalEntryForSavings(office, currencyCode, savingsControlAccount, loanId, transactionId, transactionDate,
                    totalAmount);
            createCreditJournalEntryForSavings(office, currencyCode, chargeSpecificAccount, loanId, transactionId, transactionDate,
                    totalAmount);
        }
    }

    public Office getOfficeById(final long officeId) {
        return this.officeRepository.getReferenceById(officeId);
    }

    public void createCreditJournalEntryForLoan(final Office office, final String currencyCode, final int accountMappingTypeId,
            final Long loanProductId, final Long paymentTypeId, final Long loanId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount account = getLinkedGLAccountForLoanProduct(loanProductId, accountMappingTypeId, paymentTypeId);
        createCreditJournalEntryForLoan(office, currencyCode, loanId, transactionId, transactionDate, amount, account);
    }

    public void createCreditJournalEntryForLoan(final Office office, final String currencyCode, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount, final GLAccount account) {
        createCreditJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
    }

    private void createCreditJournalEntryForClientPayments(final Office office, final String currencyCode, final GLAccount account,
            final Long clientId, final Long transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;

        String modifiedTransactionId = CLIENT_TRANSACTION_IDENTIFIER + transactionId;
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.CREDIT, amount, null, PortfolioProductType.CLIENT.getValue(), clientId, null, null, null,
                transactionId, null);
        persistJournalEntry(journalEntry);
    }

    private void createCreditJournalEntryForSavings(final Office office, final String currencyCode, final GLAccount account,
            final Long savingsId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount)
            throws DataAccessException {
        persistJournalEntry(
                buildCreditJournalEntryForSavings(office, currencyCode, account, savingsId, transactionId, transactionDate, amount));
    }

    private JournalEntry buildCreditJournalEntryForSavings(final Office office, final String currencyCode, final GLAccount account,
            final Long savingsId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount)
            throws DataAccessException {
        final boolean manualEntry = false;
        Long savingsAccountTransactionId = null;
        String modifiedTransactionId = transactionId;
        if (StringUtils.isNumeric(transactionId)) {
            savingsAccountTransactionId = Long.parseLong(transactionId);
            modifiedTransactionId = SAVINGS_TRANSACTION_IDENTIFIER + transactionId;
        }
        return JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry, transactionDate,
                JournalEntryType.CREDIT, amount, null, PortfolioProductType.SAVING.getValue(), savingsId, null, null,
                savingsAccountTransactionId, null, null);
    }

    private void createCreditJournalEntryForLoan(final Office office, final String currencyCode, final GLAccount account, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;
        Long loanTransactionId = null;
        String modifiedTransactionId = transactionId;
        if (StringUtils.isNumeric(transactionId)) {
            loanTransactionId = Long.parseLong(transactionId);
            modifiedTransactionId = LOAN_TRANSACTION_IDENTIFIER + transactionId;
        }
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.CREDIT, amount, null, PortfolioProductType.LOAN.getValue(), loanId, null,
                loanTransactionId, null, null, null);
        persistJournalEntry(journalEntry);
    }

    public void createProvisioningDebitJournalEntry(LocalDate transactionDate, Long provisioningEntryId, Office office, String currencyCode,
            GLAccount account, BigDecimal amount) {
        final boolean manualEntry = false;
        String modifiedTransactionId = PROVISIONING_TRANSACTION_IDENTIFIER + provisioningEntryId;
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.DEBIT, amount, null, PortfolioProductType.PROVISIONING.getValue(), provisioningEntryId,
                null, null, null, null, null);
        persistJournalEntry(journalEntry);
    }

    public void createProvisioningCreditJournalEntry(LocalDate transactionDate, Long provisioningEntryId, Office office,
            String currencyCode, GLAccount account, BigDecimal amount) {
        final boolean manualEntry = false;
        String modifiedTransactionId = PROVISIONING_TRANSACTION_IDENTIFIER + provisioningEntryId;
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.CREDIT, amount, null, PortfolioProductType.PROVISIONING.getValue(), provisioningEntryId,
                null, null, null, null, null);
        persistJournalEntry(journalEntry);
    }

    public void createDebitJournalEntryForLoan(final Office office, final String currencyCode, final GLAccount account, final Long loanId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;
        Long loanTransactionId = null;
        String modifiedTransactionId = transactionId;
        if (StringUtils.isNumeric(transactionId)) {
            loanTransactionId = Long.parseLong(transactionId);
            modifiedTransactionId = LOAN_TRANSACTION_IDENTIFIER + transactionId;
        }
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.DEBIT, amount, null, PortfolioProductType.LOAN.getValue(), loanId, null,
                loanTransactionId, null, null, null);
        persistJournalEntry(journalEntry);
    }

    private void createDebitJournalEntryForSavings(final Office office, final String currencyCode, final GLAccount account,
            final Long savingsId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        persistJournalEntry(
                buildDebitJournalEntryForSavings(office, currencyCode, account, savingsId, transactionId, transactionDate, amount));
    }

    private JournalEntry buildDebitJournalEntryForSavings(final Office office, final String currencyCode, final GLAccount account,
            final Long savingsId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;
        Long savingsAccountTransactionId = null;
        String modifiedTransactionId = transactionId;
        if (StringUtils.isNumeric(transactionId)) {
            savingsAccountTransactionId = Long.parseLong(transactionId);
            modifiedTransactionId = SAVINGS_TRANSACTION_IDENTIFIER + transactionId;
        }
        return JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry, transactionDate,
                JournalEntryType.DEBIT, amount, null, PortfolioProductType.SAVING.getValue(), savingsId, null, null,
                savingsAccountTransactionId, null, null);
    }

    private JournalEntry buildDebitJournalEntryForSavings(final Office office, final String currencyCode, final int accountTypeToBeDebited,
            final Long savingsProductId, final Long paymentTypeId, final Long savingsId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount debitAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId, accountTypeToBeDebited,
                paymentTypeId);
        return buildDebitJournalEntryForSavings(office, currencyCode, debitAccount, savingsId, transactionId, transactionDate, amount);
    }

    private JournalEntry buildCreditJournalEntryForSavings(final Office office, final String currencyCode,
            final int accountTypeToBeCredited, final Long savingsProductId, final Long paymentTypeId, final Long savingsId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount creditAccount = this.self.getLinkedGLAccountForSavingsProduct(savingsProductId, accountTypeToBeCredited,
                paymentTypeId);
        return buildCreditJournalEntryForSavings(office, currencyCode, creditAccount, savingsId, transactionId, transactionDate, amount);
    }

    private void createDebitJournalEntryForClientPayments(final Office office, final String currencyCode, final GLAccount account,
            final Long clientId, final Long transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;
        String modifiedTransactionId = CLIENT_TRANSACTION_IDENTIFIER + transactionId;
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.DEBIT, amount, null, PortfolioProductType.CLIENT.getValue(), clientId, null, null, null,
                transactionId, null);
        persistJournalEntry(journalEntry);
    }

    public void createJournalEntriesForShares(final Office office, final String currencyCode, final int accountTypeToDebitId,
            final int accountTypeToCreditId, final Long shareProductId, final Long paymentTypeId, final Long shareAccountId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        createDebitJournalEntryForShares(office, currencyCode, accountTypeToDebitId, shareProductId, paymentTypeId, shareAccountId,
                transactionId, transactionDate, amount);
        createCreditJournalEntryForShares(office, currencyCode, accountTypeToCreditId, shareProductId, paymentTypeId, shareAccountId,
                transactionId, transactionDate, amount);
    }

    public void createDebitJournalEntryForShares(final Office office, final String currencyCode, final int accountTypeToDebitId,
            final Long shareProductId, final Long paymentTypeId, final Long shareAccountId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount debitAccount = getLinkedGLAccountForShareProduct(shareProductId, accountTypeToDebitId, paymentTypeId);
        createDebitJournalEntryForShares(office, currencyCode, debitAccount, shareAccountId, transactionId, transactionDate, amount);
    }

    public void createCreditJournalEntryForShares(final Office office, final String currencyCode, final int accountTypeToCreditId,
            final Long shareProductId, final Long paymentTypeId, final Long shareAccountId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal amount) {
        final GLAccount creditAccount = getLinkedGLAccountForShareProduct(shareProductId, accountTypeToCreditId, paymentTypeId);
        createCreditJournalEntryForShares(office, currencyCode, creditAccount, shareAccountId, transactionId, transactionDate, amount);
    }

    public void createCashBasedJournalEntriesForSharesCharges(final Office office, final String currencyCode,
            final CashAccountsForShares accountTypeToBeDebited, final CashAccountsForShares accountTypeToBeCredited,
            final Long shareProductId, final Long paymentTypeId, final Long shareAccountId, final String transactionId,
            final LocalDate transactionDate, final BigDecimal totalAmount, final List<ChargePaymentDTO> chargePaymentDTOs) {

        createDebitJournalEntryForShares(office, currencyCode, accountTypeToBeDebited.getValue(), shareProductId, paymentTypeId,
                shareAccountId, transactionId, transactionDate, totalAmount);
        createCashBasedJournalEntryForSharesCharges(office, currencyCode, accountTypeToBeCredited, shareProductId, shareAccountId,
                transactionId, transactionDate, totalAmount, chargePaymentDTOs);
    }

    public void createCashBasedJournalEntryForSharesCharges(final Office office, final String currencyCode,
            final CashAccountsForShares accountTypeToBeCredited, final Long shareProductId, final Long shareAccountId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal totalAmount,
            final List<ChargePaymentDTO> chargePaymentDTOs) {
        final Map<GLAccount, BigDecimal> creditDetailsMap = new LinkedHashMap<>();
        for (final ChargePaymentDTO chargePaymentDTO : chargePaymentDTOs) {
            final GLAccount chargeSpecificAccount = getLinkedGLAccountForShareCharges(shareProductId, accountTypeToBeCredited.getValue(),
                    chargePaymentDTO.getChargeId());
            BigDecimal chargeSpecificAmount = chargePaymentDTO.getAmount();

            // adjust net credit amount if the account is already present in the
            // map
            if (creditDetailsMap.containsKey(chargeSpecificAccount)) {
                final BigDecimal existingAmount = creditDetailsMap.get(chargeSpecificAccount);
                chargeSpecificAmount = chargeSpecificAmount.add(existingAmount);
            }
            creditDetailsMap.put(chargeSpecificAccount, chargeSpecificAmount);
        }

        BigDecimal totalCreditedAmount = BigDecimal.ZERO;
        for (final Map.Entry<GLAccount, BigDecimal> entry : creditDetailsMap.entrySet()) {
            final GLAccount account = entry.getKey();
            final BigDecimal amount = entry.getValue();
            totalCreditedAmount = totalCreditedAmount.add(amount);
            createCreditJournalEntryForShares(office, currencyCode, account, shareAccountId, transactionId, transactionDate, amount);
        }
        if (totalAmount.compareTo(totalCreditedAmount) != 0) {
            throw new PlatformDataIntegrityException("Recent Portfolio changes w.r.t Charges for shares have Broken the accounting code",
                    "Recent Portfolio changes w.r.t Charges for shares have Broken the accounting code");
        }
    }

    public void revertCashBasedJournalEntryForSharesCharges(final Office office, final String currencyCode,
            final CashAccountsForShares accountTypeToBeCredited, final Long shareProductId, final Long shareAccountId,
            final String transactionId, final LocalDate transactionDate, final BigDecimal totalAmount,
            final List<ChargePaymentDTO> chargePaymentDTOs) {
        final Map<GLAccount, BigDecimal> creditDetailsMap = new LinkedHashMap<>();
        for (final ChargePaymentDTO chargePaymentDTO : chargePaymentDTOs) {
            final GLAccount chargeSpecificAccount = getLinkedGLAccountForShareCharges(shareProductId, accountTypeToBeCredited.getValue(),
                    chargePaymentDTO.getChargeId());
            BigDecimal chargeSpecificAmount = chargePaymentDTO.getAmount();

            // adjust net credit amount if the account is already present in the
            // map
            if (creditDetailsMap.containsKey(chargeSpecificAccount)) {
                final BigDecimal existingAmount = creditDetailsMap.get(chargeSpecificAccount);
                chargeSpecificAmount = chargeSpecificAmount.add(existingAmount);
            }
            creditDetailsMap.put(chargeSpecificAccount, chargeSpecificAmount);
        }

        BigDecimal totalCreditedAmount = BigDecimal.ZERO;
        for (final Map.Entry<GLAccount, BigDecimal> entry : creditDetailsMap.entrySet()) {
            final GLAccount account = entry.getKey();
            final BigDecimal amount = entry.getValue();
            totalCreditedAmount = totalCreditedAmount.add(amount);
            createDebitJournalEntryForShares(office, currencyCode, account, shareAccountId, transactionId, transactionDate, amount);
        }
        if (totalAmount.compareTo(totalCreditedAmount) != 0) {
            throw new PlatformDataIntegrityException("Recent Portfolio changes w.r.t Charges for shares have Broken the accounting code",
                    "Recent Portfolio changes w.r.t Charges for shares have Broken the accounting code");
        }
    }

    private void createDebitJournalEntryForShares(final Office office, final String currencyCode, final GLAccount account,
            final Long shareAccountId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;
        Long shareTransactionId = null;
        String modifiedTransactionId = transactionId;
        if (StringUtils.isNumeric(transactionId)) {
            shareTransactionId = Long.parseLong(transactionId);
            modifiedTransactionId = SHARE_TRANSACTION_IDENTIFIER + transactionId;
        }
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.DEBIT, amount, null, PortfolioProductType.SHARES.getValue(), shareAccountId, null, null,
                null, null, shareTransactionId);
        persistJournalEntry(journalEntry);
    }

    private void createCreditJournalEntryForShares(final Office office, final String currencyCode, final GLAccount account,
            final Long shareAccountId, final String transactionId, final LocalDate transactionDate, final BigDecimal amount) {
        final boolean manualEntry = false;
        Long shareTransactionId = null;
        String modifiedTransactionId = transactionId;
        if (StringUtils.isNumeric(transactionId)) {
            shareTransactionId = Long.parseLong(transactionId);
            modifiedTransactionId = SHARE_TRANSACTION_IDENTIFIER + transactionId;
        }
        final JournalEntry journalEntry = JournalEntry.createNew(office, null, account, currencyCode, modifiedTransactionId, manualEntry,
                transactionDate, JournalEntryType.CREDIT, amount, null, PortfolioProductType.SHARES.getValue(), shareAccountId, null, null,
                null, null, shareTransactionId);
        persistJournalEntry(journalEntry);
    }

    public GLAccount getLinkedGLAccountForLoanProduct(final Long loanProductId, final int accountMappingTypeId, final Long paymentTypeId) {
        GLAccount glAccount;
        if (isOrganizationAccount(accountMappingTypeId)) {
            FinancialActivityAccount financialActivityAccount = this.financialActivityAccountRepository
                    .findByFinancialActivityTypeWithNotFoundDetection(accountMappingTypeId);
            glAccount = financialActivityAccount.getGlAccount();
        } else {
            ProductToGLAccountMapping accountMapping = findCoreProductToFinAccountMapping(loanProductId,
                    PortfolioProductType.LOAN.getValue(), accountMappingTypeId);

            /****
             * Get more specific mapping for FUND source accounts (based on payment channels). Note that fund source
             * placeholder ID would be same for both cash and accrual accounts
             ***/
            if (accountMappingTypeId == CashAccountsForLoan.FUND_SOURCE.getValue()) {
                final ProductToGLAccountMapping paymentChannelSpecificAccountMapping = findPaymentTypeMapping(loanProductId,
                        PortfolioProductType.LOAN.getValue(), accountMappingTypeId, paymentTypeId);
                if (paymentChannelSpecificAccountMapping != null) {
                    accountMapping = paymentChannelSpecificAccountMapping;
                }
            }

            if (accountMapping == null) {
                throw new ProductToGLAccountMappingNotFoundException(PortfolioProductType.LOAN, loanProductId,
                        AccrualAccountsForLoan.fromInt(accountMappingTypeId).toString());

            }
            glAccount = accountMapping.getGlAccount();
        }
        return glAccount;
    }

    private GLAccount getLinkedGLAccountForLoanCharges(final Long loanProductId, final int accountMappingTypeId, final Long chargeId) {
        ProductToGLAccountMapping accountMapping = findCoreProductToFinAccountMapping(loanProductId, PortfolioProductType.LOAN.getValue(),
                accountMappingTypeId);
        /*****
         * Get more specific mappings for Charges and penalties (based on the actual charge /penalty coupled with the
         * loan product). Note the income from fees and income from penalties placeholder ID would be the same for both
         * cash and accrual based accounts
         *****/

        // Check for charge-specific mappings for all account types (not just income accounts)
        // This allows charge-specific GL account mappings for debit accounts as well
        if (chargeId != null) {
            final ProductToGLAccountMapping chargeSpecificAccountMapping = findChargeMapping(loanProductId,
                    PortfolioProductType.LOAN.getValue(), accountMappingTypeId, chargeId);
            if (chargeSpecificAccountMapping != null) {
                accountMapping = chargeSpecificAccountMapping;
            }
        }
        return accountMapping.getGlAccount();
    }

    private GLAccount getLinkedGLAccountForSavingsCharges(final Long savingsProductId, final int accountMappingTypeId,
            final Long chargeId) {

        ProductToGLAccountMapping accountMapping = findCoreProductToFinAccountMapping(savingsProductId,
                PortfolioProductType.SAVING.getValue(), accountMappingTypeId);
        /*****
         * Get more specific mappings for Charges and penalties (based on the actual charge /penalty coupled with the
         * loan product). Note the income from fees and income from penalties placeholder ID would be the same for both
         * cash and accrual based accounts
         *****/

        // Vishwas TODO: remove this condition as it should always be true

        if (accountMappingTypeId == CashAccountsForSavings.INCOME_FROM_FEES.getValue()
                || accountMappingTypeId == CashAccountsForLoan.INCOME_FROM_PENALTIES.getValue()) {
            GLAccount glAccount = chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId).getAccount();
            if (glAccount != null) {
                return glAccount;
            }
            final ProductToGLAccountMapping chargeSpecificIncomeAccountMapping = findChargeMapping(savingsProductId,
                    PortfolioProductType.SAVING.getValue(), accountMappingTypeId, chargeId);
            if (chargeSpecificIncomeAccountMapping != null) {

                accountMapping = chargeSpecificIncomeAccountMapping;
            }
        }

        return accountMapping.getGlAccount();
    }

    @Cacheable(value = "savingsProductToGLAccounts", key = "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil).getTenant().getTenantIdentifier().concat(T(java.lang.String).format('|%s|%s|%s', #savingsProductId, #accountMappingTypeId, #paymentTypeId))")
    public GLAccount getLinkedGLAccountForSavingsProduct(final Long savingsProductId, final int accountMappingTypeId,
            final Long paymentTypeId) {
        GLAccount glAccount;
        if (isOrganizationAccount(accountMappingTypeId)) {
            FinancialActivityAccount financialActivityAccount = this.financialActivityAccountRepository
                    .findByFinancialActivityTypeWithNotFoundDetection(accountMappingTypeId);
            glAccount = financialActivityAccount.getGlAccount();
        } else {
            ProductToGLAccountMapping accountMapping = findCoreProductToFinAccountMapping(savingsProductId,
                    PortfolioProductType.SAVING.getValue(), accountMappingTypeId);
            /****
             * Get more specific mapping for FUND source accounts (based on payment channels). Note that fund source
             * placeholder ID would be same for both cash and accrual accounts
             ***/
            if (accountMappingTypeId == CashAccountsForSavings.SAVINGS_REFERENCE.getValue()) {
                final ProductToGLAccountMapping paymentChannelSpecificAccountMapping = findPaymentTypeMapping(savingsProductId,
                        PortfolioProductType.SAVING.getValue(), accountMappingTypeId, paymentTypeId);
                if (paymentChannelSpecificAccountMapping != null) {
                    accountMapping = paymentChannelSpecificAccountMapping;
                }
            }
            glAccount = accountMapping.getGlAccount();
        }
        return glAccount;
    }

    private GLAccount getLinkedGLAccountForShareProduct(final Long shareProductId, final int accountMappingTypeId,
            final Long paymentTypeId) {
        GLAccount glAccount;
        if (isOrganizationAccount(accountMappingTypeId)) {
            FinancialActivityAccount financialActivityAccount = this.financialActivityAccountRepository
                    .findByFinancialActivityTypeWithNotFoundDetection(accountMappingTypeId);
            glAccount = financialActivityAccount.getGlAccount();
        } else {
            ProductToGLAccountMapping accountMapping = findCoreProductToFinAccountMapping(shareProductId,
                    PortfolioProductType.SHARES.getValue(), accountMappingTypeId);

            if (accountMappingTypeId == CashAccountsForShares.SHARES_REFERENCE.getValue()) {
                final ProductToGLAccountMapping paymentChannelSpecificAccountMapping = findPaymentTypeMapping(shareProductId,
                        PortfolioProductType.SHARES.getValue(), accountMappingTypeId, paymentTypeId);
                if (paymentChannelSpecificAccountMapping != null) {
                    accountMapping = paymentChannelSpecificAccountMapping;
                }
            }
            glAccount = accountMapping.getGlAccount();
        }
        return glAccount;
    }

    private GLAccount getLinkedGLAccountForShareCharges(final Long shareProductId, final int accountMappingTypeId, final Long chargeId) {
        ProductToGLAccountMapping accountMapping = findCoreProductToFinAccountMapping(shareProductId,
                PortfolioProductType.SHARES.getValue(), accountMappingTypeId);
        /*****
         * Get more specific mappings for Charges and penalties (based on the actual charge /penalty coupled with the
         * loan product). Note the income from fees and income from penalties placeholder ID would be the same for both
         * cash and accrual based accounts
         *****/

        final ProductToGLAccountMapping chargeSpecificIncomeAccountMapping = findChargeMapping(shareProductId,
                PortfolioProductType.SHARES.getValue(), accountMappingTypeId, chargeId);
        if (chargeSpecificIncomeAccountMapping != null) {
            accountMapping = chargeSpecificIncomeAccountMapping;
        }
        return accountMapping.getGlAccount();
    }

    private static final class JournalEntryProcessingBatchCache {

        private final Map<ProductToGLAccountMappingCacheKey, ProductToGLAccountMapping> productToGLAccountMappings = new HashMap<>();
        private int depth;

        private void incrementDepth() {
            this.depth++;
        }

        private int decrementDepth() {
            this.depth--;
            return this.depth;
        }

        private boolean contains(ProductToGLAccountMappingCacheKey cacheKey) {
            return this.productToGLAccountMappings.containsKey(cacheKey);
        }

        private ProductToGLAccountMapping get(ProductToGLAccountMappingCacheKey cacheKey) {
            return this.productToGLAccountMappings.get(cacheKey);
        }

        private void put(ProductToGLAccountMappingCacheKey cacheKey, ProductToGLAccountMapping accountMapping) {
            this.productToGLAccountMappings.put(cacheKey, accountMapping);
        }
    }

    private enum ProductToGLAccountMappingLookupType {
        CORE, PAYMENT_TYPE, CHARGE, CHARGE_OFF_REASON, WRITE_OFF_REASON, BUYDOWN_FEE_CLASSIFICATION, CAPITALIZED_INCOME_CLASSIFICATION
    }

    private static final class ProductToGLAccountMappingCacheKey {

        private final ProductToGLAccountMappingLookupType lookupType;
        private final Long productId;
        private final Integer productType;
        private final Integer financialAccountType;
        private final Long referenceId;

        private ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType lookupType, Long productId, Integer productType,
                Integer financialAccountType, Long referenceId) {
            this.lookupType = lookupType;
            this.productId = productId;
            this.productType = productType;
            this.financialAccountType = financialAccountType;
            this.referenceId = referenceId;
        }

        private static ProductToGLAccountMappingCacheKey core(Long productId, Integer productType, Integer financialAccountType) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.CORE, productId, productType,
                    financialAccountType, null);
        }

        private static ProductToGLAccountMappingCacheKey paymentType(Long productId, Integer productType, Integer financialAccountType,
                Long paymentTypeId) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.PAYMENT_TYPE, productId, productType,
                    financialAccountType, paymentTypeId);
        }

        private static ProductToGLAccountMappingCacheKey charge(Long productId, Integer productType, Integer financialAccountType,
                Long chargeId) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.CHARGE, productId, productType,
                    financialAccountType, chargeId);
        }

        private static ProductToGLAccountMappingCacheKey chargeOffReason(Long productId, Integer productType, Long chargeOffReasonId) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.CHARGE_OFF_REASON, productId, productType,
                    null, chargeOffReasonId);
        }

        private static ProductToGLAccountMappingCacheKey writeOffReason(Long productId, Integer productType, Long writeOffReasonId) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.WRITE_OFF_REASON, productId, productType, null,
                    writeOffReasonId);
        }

        private static ProductToGLAccountMappingCacheKey buydownFeeClassification(Long productId, Integer productType,
                Long classificationId) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.BUYDOWN_FEE_CLASSIFICATION, productId,
                    productType, null, classificationId);
        }

        private static ProductToGLAccountMappingCacheKey capitalizedIncomeClassification(Long productId, Integer productType,
                Long classificationId) {
            return new ProductToGLAccountMappingCacheKey(ProductToGLAccountMappingLookupType.CAPITALIZED_INCOME_CLASSIFICATION, productId,
                    productType, null, classificationId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ProductToGLAccountMappingCacheKey that)) {
                return false;
            }
            return lookupType == that.lookupType && Objects.equals(productId, that.productId)
                    && Objects.equals(productType, that.productType) && Objects.equals(financialAccountType, that.financialAccountType)
                    && Objects.equals(referenceId, that.referenceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lookupType, productId, productType, financialAccountType, referenceId);
        }
    }

    private boolean isOrganizationAccount(final int accountMappingTypeId) {
        return FinancialActivity.fromInt(accountMappingTypeId) != null;
    }

    public BigDecimal createCreditJournalEntryOrReversalForClientPayments(final Office office, final String currencyCode,
            final Long clientId, final Long transactionId, final LocalDate transactionDate, final Boolean isReversal,
            final List<ClientChargePaymentDTO> clientChargePaymentDTOs) {
        /***
         * Map to track each account affected and the net credit to be made for a particular account
         ***/
        final Map<Long, BigDecimal> creditDetailsMap = new LinkedHashMap<>();
        for (final ClientChargePaymentDTO clientChargePaymentDTO : clientChargePaymentDTOs) {
            if (clientChargePaymentDTO.getIncomeAccountId() != null) {
                final Long accountId = clientChargePaymentDTO.getIncomeAccountId();
                BigDecimal chargeSpecificAmount = clientChargePaymentDTO.getAmount();

                // adjust net credit amount if the account is already present in
                // the map
                if (creditDetailsMap.containsKey(accountId)) {
                    final BigDecimal existingAmount = creditDetailsMap.get(accountId);
                    chargeSpecificAmount = chargeSpecificAmount.add(existingAmount);
                }
                creditDetailsMap.put(accountId, chargeSpecificAmount);
            }
        }

        BigDecimal totalCreditedAmount = BigDecimal.ZERO;
        for (final Map.Entry<Long, BigDecimal> entry : creditDetailsMap.entrySet()) {
            final GLAccount account = getGLAccountById(entry.getKey());
            final BigDecimal amount = entry.getValue();
            totalCreditedAmount = totalCreditedAmount.add(amount);
            if (isReversal) {
                createDebitJournalEntryForClientPayments(office, currencyCode, account, clientId, transactionId, transactionDate, amount);
            } else {
                createCreditJournalEntryForClientPayments(office, currencyCode, account, clientId, transactionId, transactionDate, amount);
            }
        }
        return totalCreditedAmount;
    }

    public void createDebitJournalEntryOrReversalForClientChargePayments(final Office office, final String currencyCode,
            final Long clientId, final Long transactionId, final LocalDate transactionDate, final BigDecimal amount,
            final Boolean isReversal) {
        final GLAccount account = financialActivityAccountRepository
                .findByFinancialActivityTypeWithNotFoundDetection(FinancialActivity.ASSET_FUND_SOURCE.getValue()).getGlAccount();
        if (isReversal) {
            createCreditJournalEntryForClientPayments(office, currencyCode, account, clientId, transactionId, transactionDate, amount);
        } else {
            createDebitJournalEntryForClientPayments(office, currencyCode, account, clientId, transactionId, transactionDate, amount);
        }
    }

    private GLAccount getGLAccountById(final Long accountId) {
        return this.glAccountRepository.getReferenceById(accountId);
    }

    public Integer getValueForFeeOrPenaltyIncomeAccount(final String chargeRefundChargeType) {
        if (chargeRefundChargeType == null
                || !(chargeRefundChargeType.equalsIgnoreCase("P") || chargeRefundChargeType.equalsIgnoreCase("F"))) {
            String errorValue;
            errorValue = Objects.requireNonNullElse(chargeRefundChargeType, "Null");
            throw new PlatformDataIntegrityException("error.msg.chargeRefundChargeType.can.only.be.P.or.F",
                    "chargeRefundChargeType can only be P (Penalty) or F(Fee) - Value is: " + errorValue);
        }
        Integer incomeAccount;
        if (chargeRefundChargeType.equalsIgnoreCase("P")) {
            incomeAccount = AccrualAccountsForLoan.INCOME_FROM_PENALTIES.getValue();
        } else {
            incomeAccount = AccrualAccountsForLoan.INCOME_FROM_FEES.getValue();

        }
        return incomeAccount;
    }

    public List<JournalEntry> persistJournalEntries(List<JournalEntry> journalEntries) {
        if (journalEntries.isEmpty()) {
            return List.of();
        }
        final List<JournalEntry> savedJournalEntries = new ArrayList<>(journalEntries.size());
        for (JournalEntry journalEntry : journalEntries) {
            savedJournalEntries.add(persistJournalEntry(journalEntry));
        }
        return savedJournalEntries;
    }

    public JournalEntry persistJournalEntry(JournalEntry journalEntry) {
        boolean isNew = journalEntry.isNew();
        JournalEntry savedJournalEntry = this.glJournalEntryRepository.saveAndFlush(journalEntry);
        if (isNew && journalEntry.getLoanTransactionId() != null) {
            businessEventNotifierService.notifyPostBusinessEvent(new LoanJournalEntryCreatedBusinessEvent(savedJournalEntry));
        }
        return savedJournalEntry;
    }

    private void createJournalEntriesForLoanChargesInternal(final Office office, final String currencyCode, final int accountMappingTypeId,
            final Long loanProductId, final Long loanId, final String transactionId, final LocalDate transactionDate,
            final BigDecimal totalAmount, final List<ChargePaymentDTO> chargePaymentDTOs, final boolean isCredit) {
        final Map<GLAccount, BigDecimal> creditDetailsMap = new LinkedHashMap<>();

        for (final ChargePaymentDTO chargePaymentDTO : chargePaymentDTOs) {
            final Long chargeId = chargePaymentDTO.getChargeId();
            final GLAccount account = getLinkedGLAccountForLoanCharges(loanProductId, accountMappingTypeId, chargeId);
            BigDecimal amount = chargePaymentDTO.getAmount();

            creditDetailsMap.merge(account, amount, BigDecimal::add);
        }

        BigDecimal totalCreditedAmount = BigDecimal.ZERO;

        for (Map.Entry<GLAccount, BigDecimal> entry : creditDetailsMap.entrySet()) {
            GLAccount account = entry.getKey();
            BigDecimal amount = entry.getValue();
            totalCreditedAmount = totalCreditedAmount.add(amount);

            if (isCredit) {
                createCreditJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
            } else {
                createDebitJournalEntryForLoan(office, currencyCode, account, loanId, transactionId, transactionDate, amount);
            }
        }

        if (totalAmount.compareTo(totalCreditedAmount) != 0) {
            throw new PlatformDataIntegrityException(
                    "Meltdown in advanced accounting...sum of all charges is not equal to the fee charge for a transaction",
                    "Meltdown in advanced accounting...sum of all charges is not equal to the fee charge for a transaction",
                    totalCreditedAmount, totalAmount);
        }
    }
}
