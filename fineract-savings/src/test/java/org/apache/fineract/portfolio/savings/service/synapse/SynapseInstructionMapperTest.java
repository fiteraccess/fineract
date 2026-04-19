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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Direction;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.Operation;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseTransactionInstruction.TransactionType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SynapseInstructionMapperTest {

    private final SynapseInstructionMapper mapper = new SynapseInstructionMapper();

    @Test
    void interestPostingMapsToCredit() {
        SavingsAccountData account = buildAccount(100L, 10L, "EXT-100", "NGN");
        SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.INTEREST_POSTING, new BigDecimal("250.00"));

        SynapseTransactionInstruction result = mapper.map(account, tx, Operation.POST, "batch-1");

        assertThat(result.getTransactionType()).isEqualTo(TransactionType.INTEREST_POSTING);
        assertThat(result.getDirection()).isEqualTo(Direction.CREDIT);
        assertThat(result.getOperation()).isEqualTo(Operation.POST);
        assertThat(result.getAmount()).isEqualByComparingTo("250.00");
        assertThat(result.getSavingsAccountId()).isEqualTo(100L);
        assertThat(result.getOfficeId()).isEqualTo(10L);
        assertThat(result.getExternalId()).isEqualTo("EXT-100");
        assertThat(result.getCurrencyCode()).isEqualTo("NGN");
        assertThat(result.getBatchId()).isEqualTo("batch-1");
        assertThat(UUID.fromString(result.getTraceId())).isNotNull();
    }

    @Test
    void overdraftInterestMapsToDebit() {
        SavingsAccountData account = buildAccount(200L, 20L, "EXT-200", "USD");
        SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.OVERDRAFT_INTEREST, new BigDecimal("15.50"));

        SynapseTransactionInstruction result = mapper.map(account, tx, Operation.POST, "batch-2");

        assertThat(result.getTransactionType()).isEqualTo(TransactionType.OVERDRAFT_INTEREST);
        assertThat(result.getDirection()).isEqualTo(Direction.DEBIT);
        assertThat(result.getAmount()).isEqualByComparingTo("15.50");
    }

    @Test
    void withholdTaxMapsToDebit() {
        SavingsAccountData account = buildAccount(300L, 30L, null, "GBP");
        SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.WITHHOLD_TAX, new BigDecimal("5.00"));

        SynapseTransactionInstruction result = mapper.map(account, tx, Operation.POST, "batch-3");

        assertThat(result.getTransactionType()).isEqualTo(TransactionType.WITHHOLD_TAX);
        assertThat(result.getDirection()).isEqualTo(Direction.DEBIT);
    }

    @Test
    void reversalOperationIsPreserved() {
        SavingsAccountData account = buildAccount(400L, 40L, null, "NGN");
        SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.INTEREST_POSTING, new BigDecimal("100.00"));

        SynapseTransactionInstruction result = mapper.map(account, tx, Operation.REVERSE, "batch-4");

        assertThat(result.getOperation()).isEqualTo(Operation.REVERSE);
        assertThat(result.getTransactionType()).isEqualTo(TransactionType.INTEREST_POSTING);
        assertThat(result.getDirection()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void unsupportedTransactionTypeThrows() {
        SavingsAccountData account = buildAccount(500L, 50L, null, "NGN");
        SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.DEPOSIT, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> mapper.map(account, tx, Operation.POST, "batch-5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported transaction type");
    }

    @Test
    void transactionDateIsPopulatedFromImportTransaction() {
        SavingsAccountData account = buildAccount(600L, 60L, null, "NGN");
        SavingsAccountTransactionEnumData txType = txEnumData(SavingsAccountTransactionType.INTEREST_POSTING);
        // Use importInstance which sets the transactionDate field (same path as production interest posting)
        SavingsAccountTransactionData tx = SavingsAccountTransactionData.importInstance(
                new BigDecimal("50.00"), LocalDate.of(2026, 3, 15), null, null, null, null, null, null, null,
                600L, txType, null, null, null);

        SynapseTransactionInstruction result = mapper.map(account, tx, Operation.POST, "batch-6");

        assertThat(result.getTransactionDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void eachCallGeneratesUniqueTraceId() {
        SavingsAccountData account = buildAccount(700L, 70L, null, "NGN");
        SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.INTEREST_POSTING, new BigDecimal("10.00"));

        String traceId1 = mapper.map(account, tx, Operation.POST, "b").getTraceId();
        String traceId2 = mapper.map(account, tx, Operation.POST, "b").getTraceId();

        assertThat(traceId1).isNotEqualTo(traceId2);
    }

    @Nested
    class MapCharge {

        @Test
        void setsDirectionToDebit() {
            SynapseTransactionInstruction result = mapper.mapCharge(99L, 1L, 10L, "EXT-1",
                    "Outbound Transfer Fee", new BigDecimal("50.00"),
                    LocalDate.of(2026, 4, 1), "NGN", "batch-c1");

            assertThat(result.getDirection()).isEqualTo(Direction.DEBIT);
        }

        @Test
        void setsOperationToPost() {
            SynapseTransactionInstruction result = mapper.mapCharge(99L, 1L, 10L, "EXT-1",
                    "Stamp Duty", new BigDecimal("25.00"),
                    LocalDate.of(2026, 4, 1), "NGN", "batch-c2");

            assertThat(result.getOperation()).isEqualTo(Operation.POST);
        }

        @Test
        void generatesNonNullTraceId() {
            SynapseTransactionInstruction result = mapper.mapCharge(99L, 1L, 10L, "EXT-1",
                    "Outbound Transfer Fee", new BigDecimal("10.00"),
                    LocalDate.of(2026, 4, 1), "NGN", "batch-c3");

            assertThat(UUID.fromString(result.getTraceId())).isNotNull();
        }

        @Test
        void alwaysUseSavingsChargeTransactionType() {
            SynapseTransactionInstruction result = mapper.mapCharge(99L, 42L, 7L, "EXT-42",
                    "Outbound Transfer Fee", new BigDecimal("100.00"),
                    LocalDate.of(2026, 4, 15), "USD", "batch-c4");

            assertThat(result.getTransactionType()).isEqualTo(TransactionType.SAVINGS_CHARGE);
        }

        @Test
        void setsAllFieldsCorrectly() {
            SynapseTransactionInstruction result = mapper.mapCharge(99L, 42L, 7L, "EXT-42",
                    "Outbound Transfer Fee", new BigDecimal("100.00"),
                    LocalDate.of(2026, 4, 15), "USD", "batch-c4");

            assertThat(result.getSavingsAccountChargeId()).isEqualTo(99L);
            assertThat(result.getSavingsAccountId()).isEqualTo(42L);
            assertThat(result.getOfficeId()).isEqualTo(7L);
            assertThat(result.getExternalId()).isEqualTo("EXT-42");
            assertThat(result.getTransactionType()).isEqualTo(TransactionType.SAVINGS_CHARGE);
            assertThat(result.getDescription()).isEqualTo("Outbound Transfer Fee");
            assertThat(result.getAmount()).isEqualByComparingTo("100.00");
            assertThat(result.getTransactionDate()).isEqualTo(LocalDate.of(2026, 4, 15));
            assertThat(result.getCurrencyCode()).isEqualTo("USD");
            assertThat(result.getBatchId()).isEqualTo("batch-c4");
        }
    }

    @Nested
    class ResolveDirection {

        @Test
        void returnsDebitForSavingsCharge() {
            SynapseTransactionInstruction result = mapper.mapCharge(99L, 1L, 1L, null,
                    "Any Charge", BigDecimal.ONE,
                    LocalDate.of(2026, 4, 1), "NGN", "b");

            assertThat(result.getDirection()).isEqualTo(Direction.DEBIT);
        }

        @Test
        void returnsCreditForInterestPosting() {
            SavingsAccountData account = buildAccount(1L, 1L, null, "NGN");
            SavingsAccountTransactionData tx = buildTx(SavingsAccountTransactionType.INTEREST_POSTING, BigDecimal.ONE);

            SynapseTransactionInstruction result = mapper.map(account, tx, Operation.POST, "b");

            assertThat(result.getDirection()).isEqualTo(Direction.CREDIT);
        }
    }

    // --- helpers ---

    private static SavingsAccountData buildAccount(Long id, Long officeId, String externalId, String currencyCode) {
        CurrencyData currency = new CurrencyData(currencyCode);
        SavingsAccountData account = SavingsAccountData.instance(id, "SA-" + id, null, externalId,
                null, null, null, null, null, null, null, null,
                null, null, null, null, currency, null,
                null, null, null, null,
                null, null, null, false, null, false,
                null, null, false, null, false, null,
                null, null, null, false, null,
                null, false, null, null, null, null);
        ClientData client = new ClientData();
        client.setOfficeId(officeId);
        account.setClientData(client);
        return account;
    }

    private static SavingsAccountTransactionData buildTx(SavingsAccountTransactionType type, BigDecimal amount) {
        SavingsAccountTransactionEnumData txType = txEnumData(type);
        return SavingsAccountTransactionData.create(null, txType, null, null, null,
                LocalDate.of(2026, 3, 20), null, amount, null, null, false, null,
                false, null, null, LocalDate.of(2026, 3, 20));
    }

    private static SavingsAccountTransactionEnumData txEnumData(SavingsAccountTransactionType type) {
        return new SavingsAccountTransactionEnumData(type.getValue().longValue(), type.getCode(), type.getValue().toString());
    }
}

