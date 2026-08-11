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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReferenceTransactionNipValidationTest {

    @Test
    void acceptsOmittedOrEmptyFeeReferencesAndNormalizesSwitchId() {
        ReferenceTransaction.NipWithdrawalRequest omittedReferences = parse("""
                { "switchId": " nibss " }
                """);
        ReferenceTransaction.NipWithdrawalRequest emptyReferences = parse("""
                { "switchId": " nibss ", "referenceTransactions": [] }
                """);

        assertThat(omittedReferences.switchId()).isEqualTo("NIBSS");
        assertThat(omittedReferences.references()).isEmpty();
        assertThat(emptyReferences.references()).isEmpty();
    }

    @Test
    void preservesSuppliedOrderDescriptionsAndRepeatedReferenceTypes() {
        ReferenceTransaction.NipWithdrawalRequest request = parse("""
                {
                  "switchId": "nibss",
                  "referenceTransactions": [
                    { "type": "VAT", "amount": 1.65, "description": "  VAT first  " },
                    { "type": "COMMISSION", "amount": 22, "description": "First commission",
                      "breakdown": { "switchFee": { "amount": 3.75 }, "bankCommission": { "amount": 18.25 } } },
                    { "type": "VAT", "amount": 0.50, "description": "Second VAT" },
                    { "type": "COMMISSION", "amount": 10, "description": "Second commission",
                      "breakdown": { "switchFee": { "amount": 0 }, "bankCommission": { "amount": 10 } } }
                  ]
                }
                """);

        assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.VAT,
                SavingsAccountTransactionType.COMMISSION, SavingsAccountTransactionType.VAT, SavingsAccountTransactionType.COMMISSION);
        assertThat(request.references()).extracting(ReferenceTransaction::description).containsExactly("  VAT first  ", "First commission",
                "Second VAT", "Second commission");
        assertThat(request.references().get(1).breakdown().switchFee().amount()).isEqualByComparingTo("3.75");
    }

    @Test
    void acceptsUnpairedCommissionOrVat() {
        ReferenceTransaction.NipWithdrawalRequest commissionOnly = parse("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission",
                    "breakdown": { "switchFee": { "amount": 1 }, "bankCommission": { "amount": 0 } } }
                ] }
                """);
        ReferenceTransaction.NipWithdrawalRequest vatOnly = parse("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "VAT", "amount": 1, "description": "VAT" }
                ] }
                """);

        assertThat(commissionOnly.references()).extracting(ReferenceTransaction::type)
                .containsExactly(SavingsAccountTransactionType.COMMISSION);
        assertThat(vatOnly.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.VAT);
    }

    @Test
    void ignoresUnusedVatBreakdown() {
        ReferenceTransaction.NipWithdrawalRequest request = parse("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "VAT", "amount": 1, "description": "VAT",
                    "breakdown": { "switchFee": { "amount": -100 } } }
                ] }
                """);

        assertThat(request.references().getFirst().breakdown()).isNull();
    }

    @Test
    void preservesExistingEmtWithoutSwitchButRejectsUnsupportedNipReferenceTypes() {
        ReferenceTransaction.NipWithdrawalRequest emtRequest = parse("""
                { "referenceTransactions": [
                  { "type": "EMT_LEVY", "amount": 50 }
                ] }
                """);

        assertThat(emtRequest.switchId()).isNull();
        assertThat(emtRequest.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.EMT_LEVY);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "WITHDRAWAL", "amount": 1 }
                ] }
                """);
    }

    @Test
    void acceptsStandaloneVatWithoutSwitchButStillRequiresSwitchForCommissionAB339() {
        ReferenceTransaction.NipWithdrawalRequest request = parse("""
                { "referenceTransactions": [
                  { "type": "VAT", "amount": 1.50, "description": "VAT" }
                ] }
                """);

        assertThat(request.switchId()).isNull();
        assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.VAT);
        assertInvalid("""
                { "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission",
                    "breakdown": { "switchFee": { "amount": 1 }, "bankCommission": { "amount": 0 } } }
                ] }
                """);
    }

    @Test
    void rejectsMissingSwitchAndInvalidPersistenceOrAccountingPrerequisites() {
        assertInvalid("""
                { "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission",
                    "breakdown": { "switchFee": { "amount": 1 }, "bankCommission": { "amount": 0 } } }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "VAT", "amount": 0, "description": "VAT" }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "VAT", "amount": -1, "description": "VAT" }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "VAT", "amount": 1, "description": " " }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission" }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission",
                    "breakdown": { "switchFee": { "amount": 1 } } }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission",
                    "breakdown": { "switchFee": { "amount": -1 }, "bankCommission": { "amount": 2 } } }
                ] }
                """);
        assertInvalid("""
                { "switchId": "NIBSS", "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 1, "description": "Commission",
                    "breakdown": { "switchFee": { "amount": 2 }, "bankCommission": { "amount": 0 } } }
                ] }
                """);
    }

    @Test
    void doesNotApplyNipSpecificTransactionDateValidation() {
        ReferenceTransaction.NipWithdrawalRequest request = parse("""
                { "switchId": "NIBSS", "transactionDate": "24 July 2026" }
                """);

        assertThat(request.switchId()).isEqualTo("NIBSS");
    }

    @Test
    void rejectsNipFieldsOnTransferPath() {
        assertThatThrownBy(() -> ReferenceTransaction.rejectNipFields(command("""
                { "switchId": "NIBSS" }
                """), java.util.List.of())).isInstanceOf(GeneralPlatformDomainRuleException.class);
        assertThatThrownBy(() -> {
            JsonCommand command = command("""
                    { "referenceTransactions": [
                      { "type": "COMMISSION", "amount": 1, "description": "Commission",
                        "breakdown": { "switchFee": { "amount": 1 }, "bankCommission": { "amount": 0 } } }
                    ] }
                    """);
            ReferenceTransaction.rejectNipFields(command, ReferenceTransaction.parseArray(command, "referenceTransactions"));
        }).isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Nested
    class InboundNipDeposits {

        @Test
        void normalizesSwitchAndAcceptsExistingEmtLevy() {
            ReferenceTransaction.NipDepositRequest request = ReferenceTransaction.parseNipDeposit(command("""
                    { "switchId": " nibss ", "referenceTransactions": [
                      { "type": "EMT_LEVY", "amount": 50 }
                    ] }
                    """));

            assertThat(request.switchId()).isEqualTo("NIBSS");
            assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.EMT_LEVY);
        }

        @Test
        void preservesLegacyDepositWithoutSwitch() {
            ReferenceTransaction.NipDepositRequest request = ReferenceTransaction.parseNipDeposit(command("""
                    { "referenceTransactions": [
                      { "type": "EMT_LEVY", "amount": 50 }
                    ] }
                    """));

            assertThat(request.switchId()).isNull();
            assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.EMT_LEVY);
        }

        @Test
        void rejectsCommissionAndVatReferences() {
            assertInvalidDeposit("""
                    { "switchId": "NIBSS", "referenceTransactions": [
                      { "type": "COMMISSION", "amount": 1 }
                    ] }
                    """);
            assertInvalidDeposit("""
                    { "switchId": "NIBSS", "referenceTransactions": [
                      { "type": "VAT", "amount": 1 }
                    ] }
                    """);
        }

        private void assertInvalidDeposit(final String json) {
            assertThatThrownBy(() -> ReferenceTransaction.parseNipDeposit(command(json)))
                    .isInstanceOf(GeneralPlatformDomainRuleException.class);
        }
    }

    private void assertInvalid(final String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    private ReferenceTransaction.NipWithdrawalRequest parse(final String json) {
        return ReferenceTransaction.parseNipWithdrawal(command(json));
    }

    private JsonCommand command(final String json) {
        FromJsonHelper fromJsonHelper = new FromJsonHelper();
        return new JsonCommand(1L, fromJsonHelper.parse(json), fromJsonHelper);
    }
}
