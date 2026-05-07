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
package org.apache.fineract.portfolio.savings.service.synapse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountingBridgeDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountingBridgeDataHelper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;

/**
 * Replays Synapse's dormancy decision (INACTIVE / DORMANT / ESCHEAT) onto a savings account. Owns persistence and
 * journal-entry posting because the ESCHEAT transition bundles state mutation, an ESCHEAT transaction, and a Dr
 * SAVINGS_CONTROL / Cr ESCHEAT_LIABILITY journal entry into one atomic unit.
 */
@Slf4j
@RequiredArgsConstructor
public class SynapseDormancyStateApplier {

    private final SavingsAccountTransactionRepository transactionRepository;
    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final AppUserRepositoryWrapper appUserRepository;
    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;

    public record ApplyResult(SavingsAccountSubStatusEnum appliedSubStatus, SavingsAccountTransaction escheatTransaction,
            boolean alreadyApplied) {
    }

    public ApplyResult apply(final SavingsAccount account, final String traceId, final SavingsAccountSubStatusEnum target,
            final LocalDate effectiveDate, final BigDecimal escheatAmount, final String currencyCode) {
        return switch (target) {
            case INACTIVE -> applyInactive(account, target);
            case DORMANT -> applyDormant(account, target);
            case ESCHEAT -> applyEscheat(account, traceId, target, effectiveDate, escheatAmount, currencyCode);
            default -> throw new IllegalArgumentException("Unsupported dormancy target sub-status: " + target);
        };
    }

    private ApplyResult applyInactive(final SavingsAccount account, final SavingsAccountSubStatusEnum target) {
        if (target.getValue().equals(account.getSubStatus())) {
            log.debug("Dormancy replay no-op: account={} already INACTIVE", account.getId());
            return new ApplyResult(target, null, true);
        }
        account.setSubStatusInactive(false);
        savingsAccountRepositoryWrapper.saveAndFlush(account);
        return new ApplyResult(target, null, false);
    }

    private ApplyResult applyDormant(final SavingsAccount account, final SavingsAccountSubStatusEnum target) {
        if (target.getValue().equals(account.getSubStatus())) {
            log.debug("Dormancy replay no-op: account={} already DORMANT", account.getId());
            return new ApplyResult(target, null, true);
        }
        account.setSubStatusDormant();
        savingsAccountRepositoryWrapper.saveAndFlush(account);
        return new ApplyResult(target, null, false);
    }

    private ApplyResult applyEscheat(final SavingsAccount account, final String traceId, final SavingsAccountSubStatusEnum target,
            final LocalDate effectiveDate, final BigDecimal escheatAmount, final String currencyCode) {
        SavingsAccountTransaction existing = findExistingEscheat(account, traceId);
        if (existing != null) {
            log.debug("Dormancy replay no-op: ESCHEAT already exists for traceId={} on account={}", traceId, account.getId());
            return new ApplyResult(target, existing, true);
        }

        if (currencyCode != null && !currencyCode.equals(account.getCurrency().getCode())) {
            log.warn("Dormancy replay currency mismatch for account={}: callback={}, account={} (using account currency)", account.getId(),
                    currencyCode, account.getCurrency().getCode());
        }

        rejectBackdated(account, effectiveDate);
        Money amount = Money.of(account.getCurrency(), escheatAmount);
        rejectAmountMismatch(account, amount);

        AppUser systemUser = appUserRepository.fetchSystemUser();
        account.markEscheated(systemUser, effectiveDate);

        SavingsAccountTransaction escheatTransaction = null;
        if (amount.isGreaterThanZero()) {
            escheatTransaction = SavingsAccountTransaction.escheat(account, effectiveDate, amount, traceId);
            escheatTransaction = transactionRepository.saveAndFlush(escheatTransaction);
            account.addTransaction(escheatTransaction);
            account.getSummary().updateSummaryWithTransaction(account.getCurrency(), summaryWrapper, escheatTransaction);
            escheatTransaction.setRunningBalance(Money.of(account.getCurrency(), account.getSummary().getAccountBalance()));
        }

        savingsAccountRepositoryWrapper.saveAndFlush(account);

        if (escheatTransaction != null) {
            SavingsAccountingBridgeDTO bridge = SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(account,
                    List.of(escheatTransaction), false);
            journalEntryWritePlatformService.createJournalEntriesForSavings(bridge);
        }
        return new ApplyResult(target, escheatTransaction, false);
    }

    private SavingsAccountTransaction findExistingEscheat(final SavingsAccount account, final String traceId) {
        for (SavingsAccountTransaction tx : transactionRepository.findByRefNo(traceId)) {
            if (tx.getSavingsAccount().getId().equals(account.getId())
                    && tx.getTypeOf().equals(SavingsAccountTransactionType.ESCHEAT.getValue()) && tx.isNotReversed()) {
                return tx;
            }
        }
        return null;
    }

    private void rejectBackdated(final SavingsAccount account, final LocalDate effectiveDate) {
        LocalDate latestTxnDate = account.retrieveLastTransactionDate();
        if (latestTxnDate != null && effectiveDate.isBefore(latestTxnDate)) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.parameterError("error.msg.savings.escheat.backdated",
                    "Escheat effectiveDate " + effectiveDate + " is before account's latest transaction date " + latestTxnDate,
                    "effectiveDate", effectiveDate)));
        }
    }

    private void rejectAmountMismatch(final SavingsAccount account, final Money escheatAmount) {
        Money currentBalance = account.getSummary().getAccountBalance(account.getCurrency());
        if (!escheatAmount.isEqualTo(currentBalance)) {
            throw new PlatformApiDataValidationException(
                    List.of(ApiParameterError.parameterError("error.msg.savings.escheat.amount.mismatch",
                            "Escheat amount " + escheatAmount.getAmount() + " does not match account balance " + currentBalance.getAmount(),
                            "escheatAmount", escheatAmount.getAmount())));
        }
    }
}
