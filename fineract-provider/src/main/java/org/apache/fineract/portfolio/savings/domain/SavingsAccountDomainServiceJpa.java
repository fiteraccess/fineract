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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsDepositBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsWithdrawalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDTO;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.exception.DepositAccountTransactionNotAllowedException;
import org.apache.fineract.portfolio.savings.service.BalanceValidationService;
import org.apache.fineract.portfolio.savings.service.CacheableSavingsProductConfigService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavingsAccountDomainServiceJpa implements SavingsAccountDomainService {

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final BalanceValidationService balanceValidationService;
    private final EntityManager entityManager;
    private final CacheableSavingsProductConfigService cacheableSavingsProductConfigService;
    private final SavingsDailyBalanceSyncRepository savingsDailyBalanceSyncRepository;
    private final NoteRepository noteRepository;

    @Autowired
    public SavingsAccountDomainServiceJpa(final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final JournalEntryWritePlatformService journalEntryWritePlatformService,
            final ConfigurationDomainService configurationDomainService, final PlatformSecurityContext context,
            final DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository,
            final BusinessEventNotifierService businessEventNotifierService, final BalanceValidationService balanceValidationService,
            final EntityManager entityManager, CacheableSavingsProductConfigService cacheableSavingsProductConfigService,
            SavingsDailyBalanceSyncRepository savingsDailyBalanceSyncRepository, NoteRepository noteRepository) {
        this.savingsAccountRepository = savingsAccountRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.configurationDomainService = configurationDomainService;
        this.context = context;
        this.depositAccountOnHoldTransactionRepository = depositAccountOnHoldTransactionRepository;
        this.businessEventNotifierService = businessEventNotifierService;
        this.balanceValidationService = balanceValidationService;
        this.entityManager = entityManager;
        this.cacheableSavingsProductConfigService = cacheableSavingsProductConfigService;
        this.savingsDailyBalanceSyncRepository = savingsDailyBalanceSyncRepository;
        this.noteRepository = noteRepository;
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleWithdrawal(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final SavingsTransactionBooleanValues transactionBooleanValues, final boolean backdatedTxnsAllowedTill) {
        return handleWithdrawal(account, fmt, transactionDate, transactionAmount, paymentDetail, transactionBooleanValues, null,
                backdatedTxnsAllowedTill, true);
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleNipWithdrawal(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final SavingsTransactionBooleanValues transactionBooleanValues, final String switchId,
            final List<ReferenceTransaction> references, final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountTransaction withdrawal = handleWithdrawal(account, fmt, transactionDate, transactionAmount, paymentDetail,
                transactionBooleanValues, switchId, backdatedTxnsAllowedTill, false);
        final BigDecimal referenceDebit = references.stream().map(ReferenceTransaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // handleWithdrawal has already applied and validated the principal plus ordinary product withdrawal fees.
        // Validate
        // the remaining supplied debit against that updated balance before persisting a single reference row.
        this.balanceValidationService.validateBalance(account, referenceDebit, transactionBooleanValues.isExceptionForBalanceCheck());
        final List<SavingsAccountTransaction> createdReferences = applyReferenceTransactions(account, withdrawal, references,
                transactionBooleanValues.isAccountTransfer(), backdatedTxnsAllowedTill, switchId);
        for (int index = 0; index < createdReferences.size(); index++) {
            final ReferenceTransaction reference = references.get(index);
            if (reference.isNipFee()) {
                this.noteRepository.save(Note.savingsTransactionNote(account, createdReferences.get(index), reference.description()));
            }
        }
        this.businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        return withdrawal;
    }

    private SavingsAccountTransaction handleWithdrawal(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final SavingsTransactionBooleanValues transactionBooleanValues, final String switchId, final boolean backdatedTxnsAllowedTill,
            final boolean notifyBusinessEvent) {
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
                    relaxingDaysConfigForPivotDate, switchId, backdatedTxnsAllowedTill, notifyBusinessEvent);
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
        withdrawal.setSwitchId(switchId);
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

        if (notifyBusinessEvent) {
            businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        }
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
            final String switchId, final boolean backdatedTxnsAllowedTill, final boolean notifyBusinessEvent) {

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

                    BigDecimal feeAmount = calculateWithdrawalFeeForOptimizedPath(charge, transactionAmount, paymentDetail, account);

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
        final BigDecimal availableBalanceBeforeWithdrawal = account.getWithdrawableBalanceWithoutMinimumBalance();

        // Reverse accrual transactions on or after the transaction date (O(1) JPQL UPDATE)
        if (Boolean.TRUE
                .equals(cacheableSavingsProductConfigService.getSavingsProduct(account.productId()).getIsAccrualBasedAccountingEnabled())) {
            this.savingsAccountTransactionRepository.reverseAccrualTransactions(account.getId(), transactionDate);
        }

        // Create transaction directly using static factory — bypass account.withdraw() which adds to collection
        final Money transactionAmountMoney = Money.of(account.getCurrency(), transactionAmount);
        final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(account, account.office(), paymentDetail,
                transactionDate, transactionAmountMoney, refNo);
        withdrawal.setSwitchId(switchId);
        final BigDecimal principalOverdraftAmount = calculateIncrementalOverdraftAmount(availableBalanceBeforeWithdrawal,
                transactionAmount);
        if (principalOverdraftAmount.signum() > 0) {
            withdrawal.setOverdraftAmount(Money.of(account.getCurrency(), principalOverdraftAmount));
        }

        // Compute the post-withdrawal available balance for the running-balance field on the new transactions.
        // We do NOT mutate account.getSummary() — the JPQL UPDATE below applies the delta directly to the DB.
        final SavingsAccountSummary s = account.getSummary();
        final BigDecimal newPostedBalance = (s.getAccountBalance() != null ? s.getAccountBalance() : BigDecimal.ZERO)
                .subtract(transactionAmount).subtract(totalFeeAmount);
        // Available balance — posted minus active holds. Matches what recalculateDailyBalances would write
        // (it walks the timeline treating hold txns as debits). Used for setRunningBalance on the new txns
        // so the snapshot derived from running_balance_derived is hold-aware. See plan §10.2.
        final BigDecimal currentHold = (account.getOnHoldFunds() != null ? account.getOnHoldFunds() : BigDecimal.ZERO)
                .add(account.getSavingsHoldAmount() != null ? account.getSavingsHoldAmount() : BigDecimal.ZERO);
        final BigDecimal newAvailableBalance = newPostedBalance.subtract(currentHold);

        // Compute sub_status locally: reset to NONE if INACTIVE or DORMANT
        final Integer currentSubStatus = account.getSubStatus();
        final Integer newSubStatus = (currentSubStatus != null && (currentSubStatus.equals(SavingsAccountSubStatusEnum.INACTIVE.getValue())
                || currentSubStatus.equals(SavingsAccountSubStatusEnum.DORMANT.getValue()))) ? SavingsAccountSubStatusEnum.NONE.getValue()
                        : currentSubStatus;

        // Set running balance on the withdrawal transaction (available balance — hold-aware)
        withdrawal.setRunningBalance(Money.of(account.getCurrency(), newAvailableBalance));

        // Save withdrawal transaction with explicit flush to generate ID via IDENTITY strategy INSERT.
        // Must happen BEFORE entering COMMIT flush mode so the INSERT is not deferred.
        this.savingsAccountTransactionRepository.saveAndFlush(withdrawal);

        // Save fee transactions and set running balance (available balance — hold-aware)
        for (SavingsAccountTransaction feeTransaction : feeTransactions) {
            feeTransaction.setRunningBalance(Money.of(account.getCurrency(), newAvailableBalance));
            this.savingsAccountTransactionRepository.saveAndFlush(feeTransaction);
        }

        final FlushModeType originalFlushMode = this.entityManager.getFlushMode();
        this.entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            // Snapshot table is now maintained by SavingsDailyBalanceSyncService (hourly batch) — no synchronous write
            // here.

            // Narrow delta-based UPDATE: writes only the 5 columns that actually change on a withdrawal-with-fee
            // (totalWithdrawals, totalWithdrawalFees, totalFeeCharge, accountBalance, sub_status) plus version.
            // Shrinks WAL records and enables PostgreSQL HOT updates on m_savings_account, replacing the previous
            // 14-column snapshot UPDATE. Safe because this branch provably does not mutate other summary fields —
            // it bypasses account.withdraw(), summary.updateSummaryWithTransaction(), interest accrual, and
            // payCharge. The in-memory entity is intentionally NOT mutated here: with no dirty fields, the
            // commit-time autoflush has nothing to write, eliminating the redundant second UPDATE that used to fire
            // during postJournalEntriesForTransaction.
            this.savingsAccountRepository.applyWithdrawalDelta(account.getId(), transactionAmount, totalFeeAmount, newSubStatus,
                    account.getVersion());

            // Sync the in-memory entity so its accountBalance and version match what the JPQL delta just wrote to the
            // DB. Without this, any downstream code that mutates the entity in the same transaction (close, guarantor
            // release listener, etc.) hits OptimisticLockException at autoflush time because the entity's version
            // tag is stale. The fully redundant 14-column UPDATE that AB-220 was avoiding came from setting all
            // summary fields here — we now set only the two fields the rest of the code path actually reads, which
            // costs at most one minimal autoflush UPDATE per transaction.
            account.syncAfterDeltaUpdate(newPostedBalance);
        } finally {
            this.entityManager.setFlushMode(originalFlushMode);
        }

        // Post journal entries for the withdrawal transaction
        postJournalEntriesForTransaction(account, withdrawal, transactionBooleanValues.isAccountTransfer());

        // Post journal entries for fee transactions
        for (SavingsAccountTransaction feeTransaction : feeTransactions) {
            postJournalEntriesForTransaction(account, feeTransaction, transactionBooleanValues.isAccountTransfer());
        }

        if (notifyBusinessEvent) {
            businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        }

        return withdrawal;
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isAccountTransfer, final boolean isRegularTransaction, final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountTransactionType savingsAccountTransactionType = SavingsAccountTransactionType.DEPOSIT;
        return handleDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail, isAccountTransfer, isRegularTransaction,
                savingsAccountTransactionType, null, backdatedTxnsAllowedTill);
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleNipDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final String switchId,
            final List<ReferenceTransaction> references, final boolean isAccountTransfer, final boolean isRegularTransaction,
            final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountTransaction deposit = handleDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail,
                isAccountTransfer, isRegularTransaction, SavingsAccountTransactionType.DEPOSIT, switchId, backdatedTxnsAllowedTill);
        applyReferenceTransactions(account, deposit, references, isAccountTransfer, backdatedTxnsAllowedTill, switchId);
        return deposit;
    }

    private SavingsAccountTransaction handleDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isAccountTransfer, final boolean isRegularTransaction,
            final SavingsAccountTransactionType savingsAccountTransactionType, final String switchId,
            final boolean backdatedTxnsAllowedTill) {
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
                    savingsAccountTransactionType, switchId, backdatedTxnsAllowedTill);
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
        deposit.setSwitchId(switchId);
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
            final boolean isAccountTransfer, final SavingsAccountTransactionType savingsAccountTransactionType, final String switchId,
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
        if (Boolean.TRUE
                .equals(cacheableSavingsProductConfigService.getSavingsProduct(account.productId()).getIsAccrualBasedAccountingEnabled())) {
            this.savingsAccountTransactionRepository.reverseAccrualTransactions(account.getId(), transactionDate);
        }

        // Create transaction directly using static factory — bypass account.deposit() which adds to collection
        final Money amount = Money.of(account.getCurrency(), transactionAmount);
        final String refNo = UUID.randomUUID().toString();
        final SavingsAccountTransaction deposit = SavingsAccountTransaction.deposit(account, account.office(), paymentDetail,
                transactionDate, amount, savingsAccountTransactionType, refNo);
        deposit.setSwitchId(switchId);

        // Compute the post-deposit available balance for the running-balance field on the new transaction.
        // We do NOT mutate account.getSummary() — the JPQL UPDATE below applies the delta directly to the DB.
        final SavingsAccountSummary s = account.getSummary();
        final BigDecimal currentPostedBalance = s.getAccountBalance() != null ? s.getAccountBalance() : BigDecimal.ZERO;
        final BigDecimal newPostedBalance = currentPostedBalance.add(transactionAmount);
        final BigDecimal clearedOverdraft = currentPostedBalance.signum() < 0 ? transactionAmount.min(currentPostedBalance.negate())
                : BigDecimal.ZERO;
        deposit.setOverdraftAmount(Money.of(account.getCurrency(), clearedOverdraft));
        // Available balance — posted minus active holds. Matches what recalculateDailyBalances would write
        // (it walks the timeline treating hold txns as debits). Used for setRunningBalance on the new txn
        // so the snapshot derived from running_balance_derived is hold-aware. See plan §10.2.
        final BigDecimal currentHold = (account.getOnHoldFunds() != null ? account.getOnHoldFunds() : BigDecimal.ZERO)
                .add(account.getSavingsHoldAmount() != null ? account.getSavingsHoldAmount() : BigDecimal.ZERO);
        final BigDecimal newAvailableBalance = newPostedBalance.subtract(currentHold);

        // Compute sub_status locally: reset to NONE if INACTIVE or DORMANT
        final Integer currentSubStatus = account.getSubStatus();
        final Integer newSubStatus = (currentSubStatus != null && (currentSubStatus.equals(SavingsAccountSubStatusEnum.INACTIVE.getValue())
                || currentSubStatus.equals(SavingsAccountSubStatusEnum.DORMANT.getValue()))) ? SavingsAccountSubStatusEnum.NONE.getValue()
                        : currentSubStatus;

        // Set running balance on the transaction (available balance — hold-aware)
        deposit.setRunningBalance(Money.of(account.getCurrency(), newAvailableBalance));

        // Save transaction with explicit flush to generate ID via IDENTITY strategy INSERT.
        // Must happen BEFORE entering COMMIT flush mode so the INSERT is not deferred.
        this.savingsAccountTransactionRepository.saveAndFlush(deposit);

        final FlushModeType originalFlushMode = this.entityManager.getFlushMode();
        this.entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            // Snapshot table is now maintained by SavingsDailyBalanceSyncService (hourly batch) — no synchronous write
            // here.

            // Narrow delta-based UPDATE: writes only the 3 columns that actually change on a deposit
            // (totalDeposits, accountBalance, sub_status) plus version. Shrinks WAL records and enables PostgreSQL
            // HOT updates on m_savings_account, replacing the previous 14-column snapshot UPDATE. Safe because this
            // branch provably does not mutate other summary fields — it uses SavingsAccountTransaction.deposit(...)
            // (not account.deposit), and bypasses summary.updateSummaryWithTransaction(), interest accrual, and
            // payCharge. The in-memory entity is intentionally NOT mutated here: with no dirty fields, the
            // commit-time autoflush has nothing to write, eliminating the redundant second UPDATE that used to fire
            // during postJournalEntriesForTransaction. Activation does not reach this branch (gated on
            // isRegularTransaction=true at handleDeposit:375).
            this.savingsAccountRepository.applyDepositDelta(account.getId(), transactionAmount, newSubStatus, account.getVersion());

            // Sync the in-memory entity so its accountBalance and version match what the JPQL delta just wrote to the
            // DB — required for correctness across callers whose downstream code mutates the entity (see the matching
            // comment in handleWithdrawalOptimized for the full rationale).
            account.syncAfterDeltaUpdate(newPostedBalance);
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
                savingsAccountTransactionType, null, backdatedTxnsAllowedTill);
    }

    /**
     * AB-265: persists side-effect transactions (e.g. EMT Levy) asserted by an upstream system to be created alongside
     * a primary savings transaction. Each row gets the parent's {@code ref_no} so the existing bulk-reverse path
     * ({@code findByRefNo} + {@code handleReversal}) reverses parent + reference rows atomically. This method does
     * <strong>not</strong> evaluate any rule — the amount and applicability decision were made upstream.
     *
     * <p>
     * Accepted types today: {@link SavingsAccountTransactionType#EMT_LEVY},
     * {@link SavingsAccountTransactionType#COMMISSION}, {@link SavingsAccountTransactionType#VAT},
     * {@link SavingsAccountTransactionType#SIGNED_STATEMENT_FEE}. Each future side-effect must add its own branch; an
     * unsupported type fails fast so a partially-implemented sibling is never silently dropped.
     */
    @Transactional
    @Override
    public List<SavingsAccountTransaction> applyReferenceTransactions(final SavingsAccount account,
            final SavingsAccountTransaction parentTransaction, final List<ReferenceTransaction> references, final boolean isAccountTransfer,
            final boolean backdatedTxnsAllowedTill) {
        return applyReferenceTransactions(account, parentTransaction, references, isAccountTransfer, backdatedTxnsAllowedTill, null);
    }

    private List<SavingsAccountTransaction> applyReferenceTransactions(final SavingsAccount account,
            final SavingsAccountTransaction parentTransaction, final List<ReferenceTransaction> references, final boolean isAccountTransfer,
            final boolean backdatedTxnsAllowedTill, final String switchId) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }

        final String refNo = parentTransaction.getRefNo();
        if (refNo == null || refNo.isBlank()) {
            throw new IllegalStateException("Parent savings transaction " + parentTransaction.getId()
                    + " has no refNo; cannot link reference transactions for bulk reversal");
        }

        final LocalDate transactionDate = parentTransaction.getTransactionDate();
        BigDecimal transactionRunningBalance = parentTransaction.getRunningBalance(account.getCurrency()).getAmount();
        BigDecimal postedAccountBalance = account.getSummary().getAccountBalance(account.getCurrency()).getAmount();

        final List<SavingsAccountTransaction> created = new ArrayList<>(references.size());
        for (final ReferenceTransaction ref : references) {
            final BigDecimal amount = ref.amount();
            final BigDecimal balanceBeforeReference = transactionRunningBalance;
            final Money money = Money.of(account.getCurrency(), amount);
            final SavingsAccountTransaction referenceTransaction;
            if (ref.type().isEmtLevy()) {
                referenceTransaction = SavingsAccountTransaction.emtLevy(account, account.office(), transactionDate, money, refNo);
                referenceTransaction.setSwitchId(switchId);
            } else if (ref.type().isCommission()) {
                referenceTransaction = SavingsAccountTransaction.commission(account, account.office(), transactionDate, money, refNo,
                        switchId, ref.switchFeeAmount(), ref.bankCommissionAmount());
            } else if (ref.type().isVat()) {
                referenceTransaction = SavingsAccountTransaction.vat(account, account.office(), transactionDate, money, refNo, switchId);
            } else if (ref.type().isSignedStatementFee()) {
                referenceTransaction = SavingsAccountTransaction.signedStatementFee(account, account.office(), transactionDate, money,
                        refNo);
            } else {
                throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.type.not.supported",
                        "Reference transaction type " + ref.type() + " is not supported", ref.type());
            }

            transactionRunningBalance = transactionRunningBalance.subtract(amount);
            postedAccountBalance = postedAccountBalance.subtract(amount);

            referenceTransaction.setRunningBalance(Money.of(account.getCurrency(), transactionRunningBalance));
            final BigDecimal incrementalOverdraftAmount = calculateIncrementalOverdraftAmount(balanceBeforeReference, amount);
            if (incrementalOverdraftAmount.signum() > 0) {
                referenceTransaction.setOverdraftAmount(Money.of(account.getCurrency(), incrementalOverdraftAmount));
            }

            // ORDER MATTERS. handleDeposit / handleWithdrawalOptimized run applyDepositDelta / applyWithdrawalDelta
            // and then syncAfterDeltaUpdate BEFORE returning, so account.version is bumped in memory but the entity
            // is now "dirty" from EclipseLink's point of view. If we call saveAndFlush(emtLevy) first, the flush
            // cascades an UPDATE m_savings_account with a stale WHERE version clause and PostgreSQL rejects it with
            // "concurrent modification" (OLE) — which the API layer surfaces as 409.
            //
            // Sequence:
            // 1. suppress autoflush so the JPQL delta below can run without triggering the account UPDATE,
            // 2. issue the JPQL applyReferenceTransactionDelta (WHERE version = current in-memory version, which
            // matches DB because handleDeposit's sync already reconciled them),
            // 3. syncAfterDeltaUpdate so the in-memory version tracks the new DB value,
            // 4. restore flushMode and saveAndFlush the EMT row — the subsequent autoflush of the dirty account
            // now uses a version tag that matches DB, so no OLE.
            final FlushModeType originalFlushMode = this.entityManager.getFlushMode();
            this.entityManager.setFlushMode(FlushModeType.COMMIT);
            try {
                this.savingsAccountRepository.applyReferenceTransactionDelta(account.getId(), amount, account.getVersion());
                account.syncAfterDeltaUpdate(postedAccountBalance);
            } finally {
                this.entityManager.setFlushMode(originalFlushMode);
            }

            this.savingsAccountTransactionRepository.saveAndFlush(referenceTransaction);
            // Wire the fresh row into the account's aggregate so the bulk-reverse path (findByRefNo +
            // handleReversal) sees the same managed instance in account.transactions when it cascades
            // the reversed=true flag on save. Without this, reverseTransaction(isBulk=true) would flip
            // reversed=true on the freshly-loaded findByRefNo copy but the account.transactions copy
            // (unreversed) would win the JPA cascade write, leaving the EMT row un-reversed in the DB.
            if (backdatedTxnsAllowedTill) {
                account.addTransactionToExisting(referenceTransaction);
            } else {
                account.addTransaction(referenceTransaction);
            }

            postJournalEntriesForTransaction(account, referenceTransaction, isAccountTransfer, ref);
            created.add(referenceTransaction);
        }
        return created;
    }

    static BigDecimal calculateIncrementalOverdraftAmount(final BigDecimal availableBalanceBeforeDebit, final BigDecimal debitAmount) {
        final BigDecimal overdraftBeforeDebit = availableBalanceBeforeDebit.negate().max(BigDecimal.ZERO);
        final BigDecimal overdraftAfterDebit = availableBalanceBeforeDebit.subtract(debitAmount).negate().max(BigDecimal.ZERO);
        return overdraftAfterDebit.subtract(overdraftBeforeDebit).max(BigDecimal.ZERO).min(debitAmount);
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
     * {@link SavingsAccount#payCharge} to avoid adding to the transactions collection.
     */
    private BigDecimal calculateWithdrawalFeeForOptimizedPath(final SavingsAccountCharge charge, final BigDecimal transactionAmount,
            final PaymentDetail paymentDetail, final SavingsAccount account) {

        if (charge.isEnablePaymentType() && charge.isEnableFreeWithdrawal()) {
            if (paymentDetail != null && paymentDetail.getPaymentType() != null
                    && paymentDetail.getPaymentType().getName().equals(charge.getCharge().getPaymentType().getName())) {
                return handleFreeWithdrawalCountLogic(charge, transactionAmount, account);
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
            return handleFreeWithdrawalCountLogic(charge, transactionAmount, account);
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
     */
    private BigDecimal handleFreeWithdrawalCountLogic(final SavingsAccountCharge charge, final BigDecimal transactionAmount,
            final SavingsAccount account) {

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
        postJournalEntriesForTransaction(account, transaction, isAccountTransfer, null);
    }

    private void postJournalEntriesForTransaction(final SavingsAccount account, final SavingsAccountTransaction transaction,
            final boolean isAccountTransfer, final ReferenceTransaction referenceTransaction) {
        final SavingsAccountingBridgeDTO accountingBridgeData = referenceTransaction == null
                ? SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(account, List.of(transaction), isAccountTransfer)
                : SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(account, transaction, referenceTransaction,
                        isAccountTransfer);
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
            // Mark (account, txnDate) dirty so the next SA_DSYNC drain refreshes/deletes the snapshot row.
            // Pass-1 alone is insufficient when reversing the last non-reversed txn for a date — without a dirty
            // marker the snapshot would remain at its pre-reversal value forever. See plan §5.
            this.savingsDailyBalanceSyncRepository.enqueueDirty(account.getId(), savingsAccountTransaction.getTransactionDate());
        }

        // Booked daily accruals from the earliest reversed date onward were computed on balances that included the
        // reversed rows. Reverse them (their contra entries post through the same journal batch below) so the accrual
        // job re-books those dates on the revised balances and month-end posting reconciles with INTEREST_PAYABLE.
        List<SavingsAccountTransaction> reversedAccruals = List.of();
        if (account.savingsProduct().isAccrualBasedAccountingEnabled()) {
            final LocalDate earliestReversedDate = savingsAccountTransactions.stream().map(SavingsAccountTransaction::getTransactionDate)
                    .min(LocalDate::compareTo).orElseThrow();
            reversedAccruals = account.reverseAccrualsFrom(earliestReversedDate, backdatedTxnsAllowedTill);
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

        final List<Long> reversedTransactionIds = new ArrayList<>();
        for (SavingsAccountTransaction savingsAccountTransaction : savingsAccountTransactions) {
            reversedTransactionIds.add(savingsAccountTransaction.getId());
        }
        for (SavingsAccountTransaction reversedAccrual : reversedAccruals) {
            reversedTransactionIds.add(reversedAccrual.getId());
        }
        this.journalEntryWritePlatformService.linkSavingsReversalJournalEntries(reversedTransactionIds);

        return reversal;
    }
}
