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
package org.apache.fineract.portfolio.savings.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.domain.ReferenceTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

public interface SavingsAccountDomainService {

    SavingsAccountTransaction handleWithdrawal(SavingsAccount account, DateTimeFormatter fmt, LocalDate transactionDate,
            BigDecimal transactionAmount, PaymentDetail paymentDetail, SavingsTransactionBooleanValues transactionBooleanValues,
            boolean backdatedTxnsAllowedTill);

    /**
     * Records a NIP withdrawal bundle as one domain operation. The principal, supplied reference transactions and their
     * notes share the transaction boundary and the principal's root reference.
     */
    SavingsAccountTransaction handleNipWithdrawal(SavingsAccount account, DateTimeFormatter fmt, LocalDate transactionDate,
            BigDecimal transactionAmount, PaymentDetail paymentDetail, SavingsTransactionBooleanValues transactionBooleanValues,
            String switchId, List<ReferenceTransaction> references, boolean backdatedTxnsAllowedTill);

    /**
     * AB-339: posts the signed e-statement fee as the withdrawal principal (its own {@code SIGNED_STATEMENT_FEE}
     * transaction type, resolving a dedicated income GL) with VAT riding alongside as an ordinary {@code VAT} reference
     * transaction.
     */
    SavingsAccountTransaction handleSignedStatementFeeWithdrawal(SavingsAccount account, DateTimeFormatter fmt, LocalDate transactionDate,
            BigDecimal transactionAmount, PaymentDetail paymentDetail, SavingsTransactionBooleanValues transactionBooleanValues,
            List<ReferenceTransaction> references, boolean backdatedTxnsAllowedTill);

    SavingsAccountTransaction handleDeposit(SavingsAccount account, DateTimeFormatter fmt, LocalDate transactionDate,
            BigDecimal transactionAmount, PaymentDetail paymentDetail, boolean isAccountTransfer, boolean isRegularTransaction,
            boolean backdatedTxnsAllowedTill);

    SavingsAccountTransaction handleNipDeposit(SavingsAccount account, DateTimeFormatter fmt, LocalDate transactionDate,
            BigDecimal transactionAmount, PaymentDetail paymentDetail, String switchId, List<ReferenceTransaction> references,
            boolean isAccountTransfer, boolean isRegularTransaction, boolean backdatedTxnsAllowedTill);

    void postJournalEntries(SavingsAccount savingsAccount, Set<Long> existingTransactionIds, Set<Long> existingReversedTransactionIds,
            boolean backdatedTxnsAllowedTill);

    SavingsAccountTransaction handleDividendPayout(SavingsAccount account, LocalDate transactionDate, BigDecimal transactionAmount,
            boolean backdatedTxnsAllowedTill);

    SavingsAccountTransaction handleReversal(SavingsAccount account, List<SavingsAccountTransaction> savingsAccountTransactions,
            boolean backdatedTxnsAllowedTill);

    SavingsAccountTransaction handleHold(SavingsAccount account, BigDecimal amount, LocalDate transactionDate, Boolean lienAllowed);

    /**
     * AB-265: append side-effect transactions (e.g. EMT Levy) asserted by an upstream system. Each reference row is
     * created with the parent transaction's {@code ref_no} so the existing bulk-reverse (via {@code findByRefNo})
     * reverses parent + references atomically. The amount and applicability decision were made upstream — this method
     * does NOT re-evaluate any rule.
     */
    List<SavingsAccountTransaction> applyReferenceTransactions(SavingsAccount account, SavingsAccountTransaction parentTransaction,
            List<ReferenceTransaction> references, boolean isAccountTransfer, boolean backdatedTxnsAllowedTill);
}
