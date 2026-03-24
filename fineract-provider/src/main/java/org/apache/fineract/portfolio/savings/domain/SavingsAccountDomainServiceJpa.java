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

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsDepositBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsWithdrawalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDTO;
import org.apache.fineract.portfolio.savings.exception.DepositAccountTransactionNotAllowedException;
import org.apache.fineract.portfolio.savings.service.BalanceValidationService;
import org.apache.fineract.portfolio.savings.service.DailyBalanceSnapshotService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavingsAccountDomainServiceJpa implements SavingsAccountDomainService {

    private static final Logger LOG = LoggerFactory.getLogger(SavingsAccountDomainServiceJpa.class);

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final ApplicationCurrencyRepositoryWrapper applicationCurrencyRepositoryWrapper;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final BalanceValidationService balanceValidationService;
    private final SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper;
    private final DailyBalanceSnapshotService dailyBalanceSnapshotService;
    private final EntityManager entityManager;

    @Autowired
    public SavingsAccountDomainServiceJpa(final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final ApplicationCurrencyRepositoryWrapper applicationCurrencyRepositoryWrapper,
            final JournalEntryWritePlatformService journalEntryWritePlatformService,
            final ConfigurationDomainService configurationDomainService, final PlatformSecurityContext context,
            final DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository,
            final BusinessEventNotifierService businessEventNotifierService, final BalanceValidationService balanceValidationService,
            final SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper,
            final DailyBalanceSnapshotService dailyBalanceSnapshotService, final EntityManager entityManager) {
        this.savingsAccountRepository = savingsAccountRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.applicationCurrencyRepositoryWrapper = applicationCurrencyRepositoryWrapper;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.configurationDomainService = configurationDomainService;
        this.context = context;
        this.depositAccountOnHoldTransactionRepository = depositAccountOnHoldTransactionRepository;
        this.businessEventNotifierService = businessEventNotifierService;
        this.balanceValidationService = balanceValidationService;
        this.savingsAccountTransactionSummaryWrapper = savingsAccountTransactionSummaryWrapper;
        this.dailyBalanceSnapshotService = dailyBalanceSnapshotService;
        this.entityManager = entityManager;
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleWithdrawal(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final SavingsTransactionBooleanValues transactionBooleanValues, final boolean backdatedTxnsAllowedTill) {
        context.authenticatedUser();
        account.validateForAccountBlock();
        account.validateForDebitBlock();
        final Long relaxingDaysConfigForPivotDate = this.configurationDomainService.retrieveRelaxingDaysConfigForPivotDate();
        if (transactionBooleanValues.isRegularTransaction() && !account.allowWithdrawal()) {
            throw new DepositAccountTransactionNotAllowedException(account.getId(), "withdraw", account.depositAccountType());
        }

        // Optimized path for non-backdated, same-day transactions: bypass collection manipulation
        // Handles withdrawal fees inline. Falls back to legacy path only for
        // past-dated transactions (which need running balance recalculation and interest reversal handling)
        if (!DateUtils.isBefore(transactionDate, DateUtils.getBusinessLocalDate())) {
            return handleWithdrawalOptimized(account, fmt, transactionDate, transactionAmount, paymentDetail, transactionBooleanValues,
                    relaxingDaysConfigForPivotDate, backdatedTxnsAllowedTill);
        }

        // Legacy path for backdated transactions
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final boolean postReversals = this.configurationDomainService.isReversalTransactionAllowed();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final Set<Long> existingTransactionIds = new HashSet<>();
        final LocalDate postInterestOnDate = null;
        final Set<Long> existingReversedTransactionIds = new HashSet<>();

        if (backdatedTxnsAllowedTill) {
            updateTransactionDetailsWithPivotConfig(account, existingTransactionIds, existingReversedTransactionIds);
        } else {
            updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);
        }

        Integer accountType = null;
        final SavingsAccountTransactionDTO transactionDTO = new SavingsAccountTransactionDTO(fmt, transactionDate, transactionAmount,
                paymentDetail, null, accountType);
        UUID refNo = UUID.randomUUID();
        final SavingsAccountTransaction withdrawal = account.withdraw(transactionDTO, transactionBooleanValues.isApplyWithdrawFee(),
                backdatedTxnsAllowedTill, relaxingDaysConfigForPivotDate, refNo.toString());
        final MathContext mc = MathContext.DECIMAL64;

        final LocalDate today = DateUtils.getBusinessLocalDate();

        if (account.isBeforeLastPostingPeriod(transactionDate, backdatedTxnsAllowedTill)) {
            account.postInterest(mc, today, transactionBooleanValues.isInterestTransfer(), isSavingsInterestPostingAtCurrentPeriodEnd,
                    financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill, postReversals);
        } else {
            account.calculateInterestUsing(mc, today, transactionBooleanValues.isInterestTransfer(),
                    isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill,
                    postReversals);
        }

        List<DepositAccountOnHoldTransaction> depositAccountOnHoldTransactions = null;
        if (account.getOnHoldFunds().compareTo(BigDecimal.ZERO) > 0) {
            depositAccountOnHoldTransactions = this.depositAccountOnHoldTransactionRepository
                    .findBySavingsAccountAndReversedFalseOrderByCreatedDateAsc(account);
        }

        account.validateAccountBalanceDoesNotBecomeNegative(transactionAmount, transactionBooleanValues.isExceptionForBalanceCheck(),
                depositAccountOnHoldTransactions, backdatedTxnsAllowedTill);

        saveTransactionToGenerateTransactionId(withdrawal);
        // Update transactions separately
        saveUpdatedTransactionsOfSavingsAccount(account.getSavingsAccountTransactionsWithPivotConfig());
        this.savingsAccountRepository.save(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, transactionBooleanValues.isAccountTransfer(),
                backdatedTxnsAllowedTill);

        businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        return withdrawal;
    }

    /**
     * Optimized withdrawal path for non-backdated transactions. Creates and persists the transaction directly without
     * adding it to the SavingsAccount.transactions collection, avoiding O(N) collection manipulation. Uses O(1) balance
     * validation via {@link BalanceValidationService} and incremental summary updates.
     */
    private SavingsAccountTransaction handleWithdrawalOptimized(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final SavingsTransactionBooleanValues transactionBooleanValues, final Long relaxingDaysConfigForPivotDate,
            final boolean backdatedTxnsAllowedTill) {

        // --- O(1) business validations (mirroring SavingsAccount.withdraw()) ---

        // 1. Active account check
        if (!account.isTransactionsAllowed()) {
            final String defaultUserMessage = "Transaction is not allowed. Account is not active.";
            final ApiParameterError error = ApiParameterError.parameterError("error.msg.savingsaccount.transaction.account.is.not.active",
                    defaultUserMessage, "transactionDate", transactionDate.format(fmt));
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 2. Future date check
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            final String defaultUserMessage = "Transaction date cannot be in the future.";
            final ApiParameterError error = ApiParameterError.parameterError("error.msg.savingsaccount.transaction.in.the.future",
                    defaultUserMessage, "transactionDate", transactionDate.format(fmt));
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 3. Before activation date check
        if (DateUtils.isBefore(transactionDate, account.getActivationDate())) {
            final Object[] defaultUserArgs = { transactionDate.format(fmt), account.getActivationDate().format(fmt) };
            final String defaultUserMessage = "Transaction date cannot be before accounts activation date.";
            final ApiParameterError error = ApiParameterError.parameterError("error.msg.savingsaccount.transaction.before.activation.date",
                    defaultUserMessage, "transactionDate", defaultUserArgs);
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 4. Lock-in period check (withdrawal only)
        if (account.isAccountLocked(transactionDate)) {
            final String defaultUserMessage = "Withdrawal is not allowed. No withdrawals are allowed until after "
                    + account.getLockedInUntilDate().format(fmt);
            final ApiParameterError error = ApiParameterError.parameterError(
                    "error.msg.savingsaccount.transaction.withdrawals.blocked.during.lockin.period", defaultUserMessage, "transactionDate",
                    transactionDate.format(fmt), account.getLockedInUntilDate().format(fmt));
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 5. Client/group transfer date check
        account.validateActivityNotBeforeClientOrGroupTransferDate(SavingsEvent.SAVINGS_WITHDRAWAL, transactionDate);

        // Pivot date validation (defensive — same-day transactions always pass, but validates if config is enabled)
        account.validatePivotDateTransaction(transactionDate, backdatedTxnsAllowedTill, relaxingDaysConfigForPivotDate,
                account.depositAccountType().resourceName());

        // --- Handle withdrawal fees in optimized path ---
        BigDecimal totalFeeAmount = BigDecimal.ZERO;
        final List<SavingsAccountTransaction> feeTransactions = new ArrayList<>();
        final String refNo = UUID.randomUUID().toString();

        if (transactionBooleanValues.isApplyWithdrawFee()) {
            for (SavingsAccountCharge charge : account.charges()) {
                if (charge.isWithdrawalFee() && charge.isActive()) {
                    if (charge.getFreeWithdrawalCount() == null) {
                        charge.setFreeWithdrawalCount(0);
                    }

                    BigDecimal feeAmount = calculateWithdrawalFeeForOptimizedPath(charge, transactionAmount, transactionDate, paymentDetail,
                            account);

                    if (feeAmount.compareTo(BigDecimal.ZERO) > 0) {
                        totalFeeAmount = totalFeeAmount.add(feeAmount);
                        Money feeAmountMoney = Money.of(account.getCurrency(), feeAmount);
                        SavingsAccountTransaction feeTransaction = SavingsAccountTransaction.withdrawalFee(account, account.office(),
                                transactionDate, feeAmountMoney, refNo);

                        SavingsAccountChargePaidBy chargePaidBy = SavingsAccountChargePaidBy.instance(feeTransaction, charge, feeAmount);
                        feeTransaction.getSavingsAccountChargesPaid().add(chargePaidBy);

                        feeTransactions.add(feeTransaction);
                    }
                }
            }
        }

        // Validate balance including fees — must check transactionAmount + totalFeeAmount
        this.balanceValidationService.validateBalance(account, transactionAmount.add(totalFeeAmount),
                transactionBooleanValues.isExceptionForBalanceCheck());

        // Reverse accrual transactions on or after the transaction date (O(1) JPQL UPDATE)
        if (Boolean.TRUE.equals(account.isAccrualBasedAccountingEnabledOnSavingsProduct())) {
            this.savingsAccountTransactionRepository.reverseAccrualTransactions(account.getId(), transactionDate);
        }

        // Create transaction directly using static factory — bypass account.withdraw() which adds to collection
        final Money transactionAmountMoney = Money.of(account.getCurrency(), transactionAmount);
        final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(account, account.office(), paymentDetail,
                transactionDate, transactionAmountMoney, refNo);

        // Calculate new summary values locally — do NOT mutate account.getSummary() to avoid dirtying the entity
        final SavingsAccountSummary s = account.getSummary();
        final BigDecimal newTotalWithdrawals = (s.getTotalWithdrawals() != null ? s.getTotalWithdrawals() : BigDecimal.ZERO)
                .add(transactionAmount);
        final BigDecimal newAccountBalance = (s.getAccountBalance() != null ? s.getAccountBalance() : BigDecimal.ZERO)
                .subtract(transactionAmount).subtract(totalFeeAmount);
        final BigDecimal newTotalWithdrawalFees = (s.getTotalWithdrawalFees() != null ? s.getTotalWithdrawalFees() : BigDecimal.ZERO)
                .add(totalFeeAmount);
        final BigDecimal newTotalFeeCharge = (s.getTotalFeeCharge() != null ? s.getTotalFeeCharge() : BigDecimal.ZERO).add(totalFeeAmount);

        // Compute sub_status locally: reset to NONE if INACTIVE or DORMANT
        final Integer currentSubStatus = account.getSubStatus();
        final Integer newSubStatus = (currentSubStatus != null && (currentSubStatus.equals(SavingsAccountSubStatusEnum.INACTIVE.getValue())
                || currentSubStatus.equals(SavingsAccountSubStatusEnum.DORMANT.getValue()))) ? SavingsAccountSubStatusEnum.NONE.getValue()
                        : currentSubStatus;

        // Set running balance on the withdrawal transaction
        withdrawal.setRunningBalance(Money.of(account.getCurrency(), newAccountBalance));

        // Save withdrawal transaction with explicit flush to generate ID via IDENTITY strategy INSERT.
        // Must happen BEFORE entering COMMIT flush mode so the INSERT is not deferred.
        this.savingsAccountTransactionRepository.saveAndFlush(withdrawal);

        // Save fee transactions and set running balance
        for (SavingsAccountTransaction feeTransaction : feeTransactions) {
            feeTransaction.setRunningBalance(Money.of(account.getCurrency(), newAccountBalance));
            this.savingsAccountTransactionRepository.saveAndFlush(feeTransaction);
        }

        final FlushModeType originalFlushMode = this.entityManager.getFlushMode();
        this.entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            // Update daily balance snapshot for O(1) interest calculation
            this.dailyBalanceSnapshotService.updateSnapshot(account.getId(), transactionDate, newAccountBalance);

            // O(1) direct update of summary + sub_status via JPQL, using locally computed values
            // Keep existing lastInterestCalculationDate — no interest calculation is done in the optimized path
            this.savingsAccountRepository.updateSummaryDirectFromValues(account.getId(), s.getTotalDeposits(), newTotalWithdrawals,
                    s.getTotalInterestPosted(), newTotalWithdrawalFees, newTotalFeeCharge, s.getTotalPenaltyCharge(),
                    s.getTotalAnnualFees(), newAccountBalance, s.getTotalOverdraftInterestDerived(), s.getTotalWithholdTax(),
                    s.getTotalInterestEarned(), s.getLastInterestCalculationDate(), s.getInterestPostedTillDate(), newSubStatus,
                    account.getVersion());

            // Sync in-memory entity state with DB to prevent stale version on subsequent flush
            s.setTotalWithdrawals(newTotalWithdrawals);
            s.setTotalWithdrawalFees(newTotalWithdrawalFees);
            s.setTotalFeeCharge(newTotalFeeCharge);
            s.setAccountBalance(newAccountBalance);
            account.sub_status = newSubStatus;
            account.version++;
        } finally {
            this.entityManager.setFlushMode(originalFlushMode);
        }

        // Post journal entries for the withdrawal transaction
        postJournalEntriesForTransaction(account, withdrawal, transactionBooleanValues.isAccountTransfer());

        // Post journal entries for fee transactions
        for (SavingsAccountTransaction feeTransaction : feeTransactions) {
            postJournalEntriesForTransaction(account, feeTransaction, transactionBooleanValues.isAccountTransfer());
        }

        businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        return withdrawal;
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isAccountTransfer, final boolean isRegularTransaction, final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountTransactionType savingsAccountTransactionType = SavingsAccountTransactionType.DEPOSIT;
        return handleDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail, isAccountTransfer, isRegularTransaction,
                savingsAccountTransactionType, backdatedTxnsAllowedTill);
    }

    private SavingsAccountTransaction handleDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isAccountTransfer, final boolean isRegularTransaction,
            final SavingsAccountTransactionType savingsAccountTransactionType, final boolean backdatedTxnsAllowedTill) {
        context.authenticatedUser();
        account.validateForAccountBlock();
        account.validateForCreditBlock();

        if (isRegularTransaction && !account.allowDeposit()) {
            throw new DepositAccountTransactionNotAllowedException(account.getId(), "deposit", account.depositAccountType());
        }

        // Optimized path for non-backdated, same-day, regular transactions: bypass collection manipulation
        // Fall back to legacy path for non-regular transactions (e.g., activation
        // deposits which are followed by charge processing that depends on this.transactions),
        // or past-dated transactions (which need running balance recalculation and interest reversal handling)
        if (isRegularTransaction && !DateUtils.isBefore(transactionDate, DateUtils.getBusinessLocalDate())) {
            return handleDepositOptimized(account, fmt, transactionDate, transactionAmount, paymentDetail, isAccountTransfer,
                    savingsAccountTransactionType, backdatedTxnsAllowedTill);
        }

        // Legacy path for backdated transactions
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final Long relaxingDaysConfigForPivotDate = this.configurationDomainService.retrieveRelaxingDaysConfigForPivotDate();
        boolean isInterestTransfer = false;
        final Set<Long> existingTransactionIds = new HashSet<>();
        final Set<Long> existingReversedTransactionIds = new HashSet<>();

        if (backdatedTxnsAllowedTill) {
            updateTransactionDetailsWithPivotConfig(account, existingTransactionIds, existingReversedTransactionIds);
        } else {
            updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);
        }

        Integer accountType = null;
        final SavingsAccountTransactionDTO transactionDTO = new SavingsAccountTransactionDTO(fmt, transactionDate, transactionAmount,
                paymentDetail, null, accountType);
        UUID refNo = UUID.randomUUID();
        final SavingsAccountTransaction deposit = account.deposit(transactionDTO, savingsAccountTransactionType, backdatedTxnsAllowedTill,
                relaxingDaysConfigForPivotDate, refNo.toString());
        final LocalDate postInterestOnDate = null;
        final MathContext mc = MathContext.DECIMAL64;

        final LocalDate today = DateUtils.getBusinessLocalDate();
        boolean postReversals = this.configurationDomainService.isReversalTransactionAllowed();
        if (account.isBeforeLastPostingPeriod(transactionDate, backdatedTxnsAllowedTill)) {
            account.postInterest(mc, today, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth,
                    postInterestOnDate, backdatedTxnsAllowedTill, postReversals);
        } else {
            account.calculateInterestUsing(mc, today, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                    financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill, postReversals);
        }

        saveTransactionToGenerateTransactionId(deposit);

        // Update transactions separately
        saveUpdatedTransactionsOfSavingsAccount(account.getSavingsAccountTransactionsWithPivotConfig());

        this.savingsAccountRepository.saveAndFlush(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
        businessEventNotifierService.notifyPostBusinessEvent(new SavingsDepositBusinessEvent(deposit));
        return deposit;
    }

    /**
     * Optimized deposit path for non-backdated transactions. Creates and persists the transaction directly without
     * adding it to the SavingsAccount.transactions collection, avoiding O(N) collection manipulation. Uses incremental
     * summary updates via {@link SavingsAccountSummary#updateSummaryWithTransaction}.
     */
    private SavingsAccountTransaction handleDepositOptimized(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isAccountTransfer, final SavingsAccountTransactionType savingsAccountTransactionType,
            final boolean backdatedTxnsAllowedTill) {

        // --- O(1) business validations (mirroring SavingsAccount.deposit()) ---
        final String resourceTypeName = account.depositAccountType().resourceName();

        // 1. Active account check
        if (account.isNotActive()) {
            final String defaultUserMessage = "Transaction is not allowed. Account is not active.";
            final String errorCode = "error.msg." + resourceTypeName + ".transaction.account.is.not.active";
            final ApiParameterError error = ApiParameterError.parameterError(errorCode, defaultUserMessage, "transactionDate",
                    fmt != null ? transactionDate.format(fmt) : transactionDate.toString());
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 2. Future date check
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            final String defaultUserMessage = "Transaction date cannot be in the future.";
            final String errorCode = "error.msg." + resourceTypeName + ".transaction.in.the.future";
            final ApiParameterError error = ApiParameterError.parameterError(errorCode, defaultUserMessage, "transactionDate",
                    fmt != null ? transactionDate.format(fmt) : transactionDate.toString());
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 3. Before activation date check
        if (DateUtils.isBefore(transactionDate, account.getActivationDate())) {
            final String txnDateStr = fmt != null ? transactionDate.format(fmt) : transactionDate.toString();
            final String activationDateStr = fmt != null ? account.getActivationDate().format(fmt) : account.getActivationDate().toString();
            final Object[] defaultUserArgs = { txnDateStr, activationDateStr };
            final String defaultUserMessage = "Transaction date cannot be before accounts activation date.";
            final ApiParameterError error = ApiParameterError.parameterError(
                    "error.msg." + resourceTypeName + ".transaction.before.activation.date", defaultUserMessage, "transactionDate",
                    defaultUserArgs);
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            dataValidationErrors.add(error);
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        // 5. Client/group transfer date check
        account.validateActivityNotBeforeClientOrGroupTransferDate(SavingsEvent.SAVINGS_DEPOSIT, transactionDate);

        // Pivot date validation (defensive — same-day transactions always pass, but validates if config is enabled)
        final Long relaxingDaysConfigForPivotDate = this.configurationDomainService.retrieveRelaxingDaysConfigForPivotDate();
        account.validatePivotDateTransaction(transactionDate, backdatedTxnsAllowedTill, relaxingDaysConfigForPivotDate, resourceTypeName);

        // Reverse accrual transactions on or after the transaction date (O(1) JPQL UPDATE)
        if (Boolean.TRUE.equals(account.isAccrualBasedAccountingEnabledOnSavingsProduct())) {
            this.savingsAccountTransactionRepository.reverseAccrualTransactions(account.getId(), transactionDate);
        }

        // Create transaction directly using static factory — bypass account.deposit() which adds to collection
        final Money amount = Money.of(account.getCurrency(), transactionAmount);
        final String refNo = UUID.randomUUID().toString();
        final SavingsAccountTransaction deposit = SavingsAccountTransaction.deposit(account, account.office(), paymentDetail,
                transactionDate, amount, savingsAccountTransactionType, refNo);

        // Calculate new summary values locally — do NOT mutate account.getSummary() to avoid dirtying the entity
        final SavingsAccountSummary s = account.getSummary();
        final BigDecimal newTotalDeposits = (s.getTotalDeposits() != null ? s.getTotalDeposits() : BigDecimal.ZERO).add(transactionAmount);
        final BigDecimal newAccountBalance = (s.getAccountBalance() != null ? s.getAccountBalance() : BigDecimal.ZERO)
                .add(transactionAmount);

        // Compute sub_status locally: reset to NONE if INACTIVE or DORMANT
        final Integer currentSubStatus = account.getSubStatus();
        final Integer newSubStatus = (currentSubStatus != null && (currentSubStatus.equals(SavingsAccountSubStatusEnum.INACTIVE.getValue())
                || currentSubStatus.equals(SavingsAccountSubStatusEnum.DORMANT.getValue()))) ? SavingsAccountSubStatusEnum.NONE.getValue()
                        : currentSubStatus;

        // Set running balance on the transaction using locally computed balance
        deposit.setRunningBalance(Money.of(account.getCurrency(), newAccountBalance));

        // Save transaction with explicit flush to generate ID via IDENTITY strategy INSERT.
        // Must happen BEFORE entering COMMIT flush mode so the INSERT is not deferred.
        this.savingsAccountTransactionRepository.saveAndFlush(deposit);

        final FlushModeType originalFlushMode = this.entityManager.getFlushMode();
        this.entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            // Update daily balance snapshot for O(1) interest calculation
            this.dailyBalanceSnapshotService.updateSnapshot(account.getId(), transactionDate, newAccountBalance);

            // O(1) direct update of summary + sub_status via JPQL, using locally computed values
            // Keep existing lastInterestCalculationDate — no interest calculation is done in the optimized path
            this.savingsAccountRepository.updateSummaryDirectFromValues(account.getId(), newTotalDeposits, s.getTotalWithdrawals(),
                    s.getTotalInterestPosted(), s.getTotalWithdrawalFees(), s.getTotalFeeCharge(), s.getTotalPenaltyCharge(),
                    s.getTotalAnnualFees(), newAccountBalance, s.getTotalOverdraftInterestDerived(), s.getTotalWithholdTax(),
                    s.getTotalInterestEarned(), s.getLastInterestCalculationDate(), s.getInterestPostedTillDate(), newSubStatus,
                    account.getVersion());

            // Sync in-memory entity state with DB to prevent stale version on subsequent flush
            // (e.g., when activate() calls saveAndFlush after this optimized deposit path)
            s.setTotalDeposits(newTotalDeposits);
            s.setAccountBalance(newAccountBalance);
            account.sub_status = newSubStatus;
            account.version++;
        } finally {
            this.entityManager.setFlushMode(originalFlushMode);
        }

        // Post journal entries for the single transaction
        postJournalEntriesForTransaction(account, deposit, isAccountTransfer);

        businessEventNotifierService.notifyPostBusinessEvent(new SavingsDepositBusinessEvent(deposit));

        return deposit;
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleHold(final SavingsAccount account, BigDecimal amount, LocalDate transactionDate,
            Boolean lienAllowed) {
        return SavingsAccountTransaction.holdAmount(account, account.office(), null, transactionDate,
                Money.of(account.getCurrency(), amount), lienAllowed);
    }

    @Override
    public SavingsAccountTransaction handleDividendPayout(final SavingsAccount account, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final boolean backdatedTxnsAllowedTill) {
        final DateTimeFormatter fmt = null;
        final PaymentDetail paymentDetail = null;
        final boolean isAccountTransfer = false;
        final boolean isRegularTransaction = true;
        final SavingsAccountTransactionType savingsAccountTransactionType = SavingsAccountTransactionType.DIVIDEND_PAYOUT;
        return handleDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail, isAccountTransfer, isRegularTransaction,
                savingsAccountTransactionType, backdatedTxnsAllowedTill);
    }

    private void updateExistingTransactionsDetails(SavingsAccount account, Set<Long> existingTransactionIds,
            Set<Long> existingReversedTransactionIds) {
        existingTransactionIds.addAll(account.findExistingTransactionIds());
        existingReversedTransactionIds.addAll(account.findExistingReversedTransactionIds());
    }

    private Long saveTransactionToGenerateTransactionId(final SavingsAccountTransaction transaction) {
        this.savingsAccountTransactionRepository.saveAndFlush(transaction);
        return transaction.getId();
    }

    private void saveUpdatedTransactionsOfSavingsAccount(final List<SavingsAccountTransaction> savingsAccountTransactions) {
        this.savingsAccountTransactionRepository.saveAll(savingsAccountTransactions);
    }

    private void updateTransactionDetailsWithPivotConfig(final SavingsAccount account, Set<Long> existingTransactionIds,
            Set<Long> existingReversedTransactionIds) {
        existingTransactionIds.addAll(account.findCurrentTransactionIdsWithPivotDateConfig());
        existingReversedTransactionIds.addAll(account.findCurrentReversedTransactionIdsWithPivotDateConfig());
    }

    /**
     * Calculates the withdrawal fee for a single charge in the optimized path. Replicates the logic from
     * {@link SavingsAccount#payWithdrawalFee} but calls {@link SavingsAccountCharge#pay} directly instead of
     * {@link SavingsAccount#payCharge} to avoid adding to the transactions collection.
     */
    private BigDecimal calculateWithdrawalFeeForOptimizedPath(final SavingsAccountCharge charge, final BigDecimal transactionAmount,
            final LocalDate transactionDate, final PaymentDetail paymentDetail, final SavingsAccount account) {

        if (charge.isEnablePaymentType() && charge.isEnableFreeWithdrawal()) {
            if (paymentDetail != null && paymentDetail.getPaymentType() != null
                    && paymentDetail.getPaymentType().getName().equals(charge.getCharge().getPaymentType().getName())) {
                return handleFreeWithdrawalCountLogic(charge, transactionAmount, transactionDate, account);
            }
            return BigDecimal.ZERO;
        } else if (charge.isEnablePaymentType()) {
            if (paymentDetail != null && paymentDetail.getPaymentType() != null
                    && paymentDetail.getPaymentType().getName().equals(charge.getCharge().getPaymentType().getName())) {
                charge.updateWithdralFeeAmount(transactionAmount);
                BigDecimal feeAmount = charge.getAmountOutstanding(account.getCurrency()).getAmount();
                charge.pay(account.getCurrency(), Money.of(account.getCurrency(), feeAmount));
                return feeAmount;
            }
            return BigDecimal.ZERO;
        } else if (!charge.isEnablePaymentType() && charge.isEnableFreeWithdrawal()) {
            return handleFreeWithdrawalCountLogic(charge, transactionAmount, transactionDate, account);
        } else {
            // Normal withdraw — always charge
            charge.updateWithdralFeeAmount(transactionAmount);
            BigDecimal feeAmount = charge.getAmountOutstanding(account.getCurrency()).getAmount();
            charge.pay(account.getCurrency(), Money.of(account.getCurrency(), feeAmount));
            return feeAmount;
        }
    }

    /**
     * Handles the free withdrawal count logic for the optimized path. Replicates the logic from
     * {@link SavingsAccount#resetFreeChargeDaysCount} and {@link SavingsAccount#countValidation}.
     */
    private BigDecimal handleFreeWithdrawalCountLogic(final SavingsAccountCharge charge, final BigDecimal transactionAmount,
            final LocalDate transactionDate, final SavingsAccount account) {

        LocalDate resetDate = charge.getResetChargeDate();
        Integer restartPeriod = charge.getRestartFrequency();
        boolean withinResetPeriod;

        if (charge.getRestartFrequencyEnum() == 2) { // months
            LocalDate localDate = DateUtils.getBusinessLocalDate();
            LocalDate resetLocalDate = (resetDate == null) ? account.getActivationDate() : resetDate;
            LocalDate gapIntervalMonth = resetLocalDate.plusMonths(restartPeriod);
            withinResetPeriod = YearMonth.from(localDate).isBefore(YearMonth.from(gapIntervalMonth));
        } else { // days
            long completedDays;
            if (resetDate == null) {
                completedDays = ChronoUnit.DAYS.between(DateUtils.getBusinessLocalDate(), account.getActivationDate());
            } else {
                completedDays = ChronoUnit.DAYS.between(DateUtils.getBusinessLocalDate(), resetDate);
            }
            withinResetPeriod = ((int) completedDays) < restartPeriod;
        }

        if (withinResetPeriod) {
            // Count validation
            if (charge.getFreeWithdrawalCount() < charge.getFrequencyFreeWithdrawalCharge()) {
                charge.setFreeWithdrawalCount(charge.getFreeWithdrawalCount() + 1);
                charge.updateNoWithdrawalFee();
                return BigDecimal.ZERO;
            } else {
                // Free count exceeded — charge the fee
                charge.updateWithdralFeeAmount(transactionAmount);
                BigDecimal feeAmount = charge.getAmountOutstanding(account.getCurrency()).getAmount();
                charge.pay(account.getCurrency(), Money.of(account.getCurrency(), feeAmount));
                return feeAmount;
            }
        } else {
            // Reset period expired — discount (reset count to 1)
            charge.setFreeWithdrawalCount(1);
            charge.setDiscountDueDate(DateUtils.getBusinessLocalDate());
            charge.updateNoWithdrawalFee();
            return BigDecimal.ZERO;
        }
    }

    private void postJournalEntries(final SavingsAccount savingsAccount, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, boolean isAccountTransfer, final boolean backdatedTxnsAllowedTill) {

        final SavingsAccountingBridgeDTO accountingBridgeData = SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(savingsAccount,
                SavingsAccountingBridgeDataHelper.findNewTransactions(savingsAccount, existingTransactionIds,
                        existingReversedTransactionIds, backdatedTxnsAllowedTill),
                isAccountTransfer);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData, savingsAccount.office());
    }

    /**
     * Posts journal entries synchronously for a single transaction. Builds the accounting bridge data directly from the
     * transaction, avoiding O(N) iteration over all transactions.
     */
    private void postJournalEntriesForTransaction(final SavingsAccount account, final SavingsAccountTransaction transaction,
            final boolean isAccountTransfer) {
        final SavingsAccountingBridgeDTO accountingBridgeData = SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(account,
                List.of(transaction), isAccountTransfer);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData, account.office());
    }

    @Transactional
    @Override
    public void postJournalEntries(final SavingsAccount account, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean backdatedTxnsAllowedTill) {

        final boolean isAccountTransfer = false;
        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
    }

    @Override
    public SavingsAccountTransaction handleReversal(SavingsAccount account, List<SavingsAccountTransaction> savingsAccountTransactions,
            boolean backdatedTxnsAllowedTill) {

        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final Long relaxingDaysConfigForPivotDate = this.configurationDomainService.retrieveRelaxingDaysConfigForPivotDate();
        final boolean postReversals = true;
        final Set<Long> existingTransactionIds = new HashSet<>();
        final Set<Long> existingReversedTransactionIds = new HashSet<>();

        if (backdatedTxnsAllowedTill) {
            updateTransactionDetailsWithPivotConfig(account, existingTransactionIds, existingReversedTransactionIds);
        } else {
            updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);
        }
        List<SavingsAccountTransaction> newTransactions = new ArrayList<>();
        SavingsAccountTransaction reversal = null;

        Set<SavingsAccountChargePaidBy> chargePaidBySet = null;
        for (SavingsAccountTransaction savingsAccountTransaction : savingsAccountTransactions) {
            reversal = SavingsAccountTransaction.reversal(savingsAccountTransaction);
            chargePaidBySet = savingsAccountTransaction.getSavingsAccountChargesPaid();
            reversal.getSavingsAccountChargesPaid().addAll(chargePaidBySet);
            account.undoTransaction(savingsAccountTransaction);
            if (postReversals) {
                newTransactions.add(reversal);
            }
        }

        boolean isInterestTransfer = false;
        LocalDate postInterestOnDate = null;
        final LocalDate today = DateUtils.getBusinessLocalDate();
        final MathContext mc = new MathContext(15, MoneyHelper.getRoundingMode());
        for (SavingsAccountTransaction savingsAccountTransaction : savingsAccountTransactions) {
            if (savingsAccountTransaction.isPostInterestCalculationRequired()
                    && account.isBeforeLastPostingPeriod(savingsAccountTransaction.getTransactionDate(), backdatedTxnsAllowedTill)) {

                account.postInterest(mc, today, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth,
                        postInterestOnDate, backdatedTxnsAllowedTill, postReversals);
            } else {
                account.calculateInterestUsing(mc, today, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                        financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill, postReversals);
            }
            account.validatePivotDateTransaction(savingsAccountTransaction.getTransactionDate(), backdatedTxnsAllowedTill,
                    relaxingDaysConfigForPivotDate, "savingsaccount");
            account.validateAccountBalanceDoesNotBecomeNegativeMinimal(savingsAccountTransaction.getAmount(), false);
            account.activateAccountBasedOnBalance();
        }
        this.savingsAccountRepository.save(account);
        newTransactions.addAll(account.getSavingsAccountTransactionsWithPivotConfig());
        this.savingsAccountTransactionRepository.saveAll(newTransactions);
        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, false, backdatedTxnsAllowedTill);

        return reversal;
    }
}
