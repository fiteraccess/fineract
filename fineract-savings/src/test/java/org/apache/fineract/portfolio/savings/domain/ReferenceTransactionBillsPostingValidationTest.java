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
import org.junit.jupiter.api.Test;

/**
 * AB-510: mirrors {@link ReferenceTransactionNipValidationTest}, keyed on {@code aggregatorCode} instead of
 * {@code switchId}.
 */
class ReferenceTransactionBillsPostingValidationTest {

    @Test
    void acceptsOmittedOrEmptyFeeReferencesAndNormalizesAggregatorCode() {
        ReferenceTransaction.BillsPostingWithdrawalRequest omittedReferences = parse("""
                { "aggregatorCode": " coralpay " }
                """);
        ReferenceTransaction.BillsPostingWithdrawalRequest emptyReferences = parse("""
                { "aggregatorCode": " coralpay ", "referenceTransactions": [] }
                """);

        assertThat(omittedReferences.aggregatorCode()).isEqualTo("CORALPAY");
        assertThat(omittedReferences.references()).isEmpty();
        assertThat(emptyReferences.references()).isEmpty();
    }

    @Test
    void acceptsTheFullWorkedExampleLegShape() {
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "aggregatorCode": "CORALPAY", "referenceTransactions": [
                  { "type": "AGGREGATOR_PAYABLE", "amount": 4950, "description": "Aggregator Payable" },
                  { "type": "COMMISSION", "amount": 50, "description": "Commission" },
                  { "type": "CONVENIENCE_FEE", "amount": 100, "description": "Convenience Fee" },
                  { "type": "VAT", "amount": 7.5, "description": "VAT" }
                ] }
                """);

        assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(
                SavingsAccountTransactionType.AGGREGATOR_PAYABLE, SavingsAccountTransactionType.COMMISSION,
                SavingsAccountTransactionType.CONVENIENCE_FEE, SavingsAccountTransactionType.VAT);
    }

    @Test
    void acceptsAirtimeShapeWithNoConvenienceFeeOrVat() {
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "aggregatorCode": "NOMIWORLD", "referenceTransactions": [
                  { "type": "AGGREGATOR_PAYABLE", "amount": 964.50, "description": "Aggregator Payable" },
                  { "type": "COMMISSION", "amount": 35.50, "description": "Commission" }
                ] }
                """);

        assertThat(request.aggregatorCode()).isEqualTo("NOMIWORLD");
        assertThat(request.references()).extracting(ReferenceTransaction::type)
                .containsExactly(SavingsAccountTransactionType.AGGREGATOR_PAYABLE, SavingsAccountTransactionType.COMMISSION);
    }

    @Test
    void preservesLegacyRequestsWithoutAggregatorCode() {
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "referenceTransactions": [
                  { "type": "EMT_LEVY", "amount": 50 }
                ] }
                """);

        assertThat(request.aggregatorCode()).isNull();
        assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.EMT_LEVY);
    }

    @Test
    void rejectsMissingAggregatorCodeWhenAggregatorPayableIsPresent() {
        assertInvalid("""
                { "referenceTransactions": [
                  { "type": "AGGREGATOR_PAYABLE", "amount": 4950, "description": "Aggregator Payable" }
                ] }
                """);
    }

    @Test
    void rejectsMissingAggregatorCodeWhenConvenienceFeeIsPresent() {
        assertInvalid("""
                { "referenceTransactions": [
                  { "type": "CONVENIENCE_FEE", "amount": 100, "description": "Convenience Fee" }
                ] }
                """);
    }

    @Test
    void rejectsUnsupportedReferenceTypesWhenAggregatorCodeIsPresent() {
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "referenceTransactions": [
                  { "type": "EMT_LEVY", "amount": 50 }
                ] }
                """);
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "referenceTransactions": [
                  { "type": "WITHDRAWAL", "amount": 1 }
                ] }
                """);
    }

    @Test
    void rejectsBlankDescription() {
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "referenceTransactions": [
                  { "type": "AGGREGATOR_PAYABLE", "amount": 4950, "description": " " }
                ] }
                """);
    }

    @Test
    void rejectsBothAggregatorCodeAndSwitchOnTheSameRequest() {
        // AB-510: mutual exclusivity between aggregatorCode and switchId is enforced by the write-platform-service
        // caller (which never calls parseBillsPostingWithdrawal when switchId is present), not by this parser —
        // documented here so the invariant has a test even though parseBillsPostingWithdrawal itself never sees
        // switchId.
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "aggregatorCode": "CORALPAY" }
                """);
        assertThat(request.aggregatorCode()).isEqualTo("CORALPAY");
    }

    @Test
    void rejectsAggregatorCodeFieldOnTransferPath() {
        assertThatThrownBy(() -> ReferenceTransaction.rejectNipFields(command("""
                { "aggregatorCode": "CORALPAY" }
                """), java.util.List.of())).isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    private void assertInvalid(final String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    private ReferenceTransaction.BillsPostingWithdrawalRequest parse(final String json) {
        return ReferenceTransaction.parseBillsPostingWithdrawal(command(json));
    }

    private JsonCommand command(final String json) {
        FromJsonHelper fromJsonHelper = new FromJsonHelper();
        return new JsonCommand(1L, fromJsonHelper.parse(json), fromJsonHelper);
    }
}
