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
package org.apache.fineract.portfolio.savings.data.synapse;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * Represents a single interest-posting instruction to be sent to Synapse. Each instruction is idempotent via its
 * {@code traceId}.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class SynapseTransactionInstruction {

    /** UUID idempotency key — Synapse must deduplicate on this. */
    private final String traceId;

    private final Long savingsAccountId;
    private final Long officeId;

    /** Savings account external ID, if available. */
    private final String externalId;

    // --- Transaction semantics ---

    private final TransactionType transactionType;
    private final Direction direction;
    private final Operation operation;

    // --- Monetary ---

    private final BigDecimal amount;
    private final BigDecimal overdraftAmount;
    private final LocalDate transactionDate;
    private final String currencyCode;

    // --- Correlation ---

    /** Reference number from the original SavingsAccountTransactionData. */
    private final String refNo;

    /** Original transaction ID when this is a reversal. */
    private final Long originalTransactionId;

    /** Scheduler run identifier — groups instructions into a single batch. */
    private final String batchId;

    public enum TransactionType {
        INTEREST_POSTING, OVERDRAFT_INTEREST, WITHHOLD_TAX
    }

    public enum Direction {
        CREDIT, DEBIT
    }

    public enum Operation {
        POST, REVERSE
    }
}
