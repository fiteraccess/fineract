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
import java.util.UUID;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseDormancyStatusInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Direction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Operation;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.TransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;

public class SynapseInstructionMapper {

    public SynapseTransactionInstruction map(SavingsAccountData account, SavingsAccountTransactionData tx, Operation operation,
            String batchId) {
        TransactionType txType = resolveTransactionType(tx.getTransactionType());
        Direction direction = resolveDirection(txType);

        return SynapseTransactionInstruction.builder().traceId(UUID.randomUUID().toString()).savingsAccountId(account.getId())
                .officeId(account.officeId()).externalId(account.getExternalId()).transactionType(txType).direction(direction)
                .operation(operation).amount(tx.getAmount()).overdraftAmount(tx.getOverdraftAmount())
                .transactionDate(tx.getTransactionDate()).currencyCode(account.getCurrency().getCode()).refNo(tx.getRefNo())
                .originalTransactionId(tx.getOriginalTransactionId()).batchId(batchId).build();
    }

    private TransactionType resolveTransactionType(SavingsAccountTransactionEnumData txType) {
        if (txType.isInterestPosting()) {
            return TransactionType.INTEREST_POSTING;
        }
        if (txType.isIncomeFromInterest()) {
            return TransactionType.OVERDRAFT_INTEREST;
        }
        if (txType.isWithholdTax()) {
            return TransactionType.WITHHOLD_TAX;
        }
        throw new IllegalArgumentException("Unsupported transaction type for Synapse mapping: " + txType.getCode());
    }

    public SynapseTransactionInstruction mapCharge(Long savingsAccountChargeId, Long savingsAccountId, Long officeId, String externalId,
            String description, BigDecimal amount, LocalDate transactionDate, String currencyCode, String batchId) {
        Direction direction = resolveDirection(TransactionType.SAVINGS_CHARGE);

        return SynapseTransactionInstruction.builder().traceId(UUID.randomUUID().toString()).savingsAccountChargeId(savingsAccountChargeId)
                .savingsAccountId(savingsAccountId).officeId(officeId).externalId(externalId)
                .transactionType(TransactionType.SAVINGS_CHARGE).direction(direction).operation(Operation.POST).amount(amount)
                .description(description).transactionDate(transactionDate).currencyCode(currencyCode).batchId(batchId).build();
    }

    public SynapseDormancyStatusInstruction mapDormancyStatus(SavingsAccount account, SavingsAccountSubStatusEnum targetSubStatus,
            LocalDate effectiveDate, String transitionReason) {
        return SynapseDormancyStatusInstruction.builder().traceId(UUID.randomUUID().toString()).savingsAccountId(account.getId())
                .clientId(account.clientId()).officeId(account.officeId())
                .previousSubStatus(SavingsAccountSubStatusEnum.fromInt(account.getSubStatus())).targetSubStatus(targetSubStatus)
                .effectiveDate(effectiveDate).transitionReason(transitionReason).currencyCode(account.getCurrency().getCode()).build();
    }

    private Direction resolveDirection(TransactionType txType) {
        return switch (txType) {
            case INTEREST_POSTING -> Direction.CREDIT;
            case OVERDRAFT_INTEREST, WITHHOLD_TAX, SAVINGS_CHARGE -> Direction.DEBIT;
        };
    }
}
