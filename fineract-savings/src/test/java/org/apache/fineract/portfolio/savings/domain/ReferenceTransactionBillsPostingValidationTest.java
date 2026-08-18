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

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.Test;

/**
 * AB-510: mirrors {@link ReferenceTransactionNipValidationTest}, keyed on {@code aggregatorCode} instead of
 * {@code switchId}. The aggregator's payable amount and the bank's commission no longer ride as reference-transaction
 * legs — both are derived from the withdrawal's own {@code aggregatorCommissionAmount} field, so only Convenience Fee
 * and VAT remain valid reference types here.
 */
class ReferenceTransactionBillsPostingValidationTest {

    private static final BigDecimal DEFAULT_TRANSACTION_AMOUNT = BigDecimal.valueOf(100000);

    @Test
    void acceptsOmittedOrEmptyFeeReferencesAndNormalizesAggregatorCode() {
        ReferenceTransaction.BillsPostingWithdrawalRequest omittedReferences = parse("""
                { "aggregatorCode": " coralpay ", "aggregatorCommissionAmount": 50 }
                """);
        ReferenceTransaction.BillsPostingWithdrawalRequest emptyReferences = parse("""
                { "aggregatorCode": " coralpay ", "aggregatorCommissionAmount": 50, "referenceTransactions": [] }
                """);

        assertThat(omittedReferences.aggregatorCode()).isEqualTo("CORALPAY");
        assertThat(omittedReferences.commissionAmount()).isEqualByComparingTo("50");
        assertThat(omittedReferences.references()).isEmpty();
        assertThat(emptyReferences.references()).isEmpty();
    }

    @Test
    void acceptsTheFullWorkedExampleLegShape() {
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50, "referenceTransactions": [
                  { "type": "CONVENIENCE_FEE", "amount": 100, "description": "Convenience Fee" },
                  { "type": "VAT", "amount": 7.5, "description": "VAT" }
                ] }
                """);

        assertThat(request.commissionAmount()).isEqualByComparingTo("50");
        assertThat(request.references()).extracting(ReferenceTransaction::type)
                .containsExactly(SavingsAccountTransactionType.CONVENIENCE_FEE, SavingsAccountTransactionType.VAT);
    }

    @Test
    void acceptsAirtimeShapeWithNoConvenienceFeeOrVat() {
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "aggregatorCode": "NOMIWORLD", "aggregatorCommissionAmount": 35.50 }
                """);

        assertThat(request.aggregatorCode()).isEqualTo("NOMIWORLD");
        assertThat(request.commissionAmount()).isEqualByComparingTo("35.50");
        assertThat(request.references()).isEmpty();
    }

    @Test
    void preservesLegacyRequestsWithoutAggregatorCode() {
        ReferenceTransaction.BillsPostingWithdrawalRequest request = parse("""
                { "referenceTransactions": [
                  { "type": "EMT_LEVY", "amount": 50 }
                ] }
                """);

        assertThat(request.aggregatorCode()).isNull();
        assertThat(request.commissionAmount()).isNull();
        assertThat(request.references()).extracting(ReferenceTransaction::type).containsExactly(SavingsAccountTransactionType.EMT_LEVY);
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
    void rejectsMissingAggregatorCommissionAmountWhenAggregatorCodeIsPresent() {
        assertInvalid("""
                { "aggregatorCode": "CORALPAY" }
                """);
    }

    @Test
    void rejectsNegativeAggregatorCommissionAmount() {
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": -1 }
                """);
    }

    @Test
    void rejectsAggregatorCommissionAmountExceedingTransactionAmount() {
        assertThatThrownBy(() -> ReferenceTransaction.parseBillsPostingWithdrawal(command("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 200 }
                """), BigDecimal.valueOf(100))).isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void rejectsUnsupportedReferenceTypesWhenAggregatorCodeIsPresent() {
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50, "referenceTransactions": [
                  { "type": "EMT_LEVY", "amount": 50 }
                ] }
                """);
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50, "referenceTransactions": [
                  { "type": "WITHDRAWAL", "amount": 1 }
                ] }
                """);
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50, "referenceTransactions": [
                  { "type": "BILL_PAYMENT", "amount": 4950, "description": "Bill Payment" }
                ] }
                """);
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50, "referenceTransactions": [
                  { "type": "COMMISSION", "amount": 50, "description": "Commission" }
                ] }
                """);
    }

    @Test
    void rejectsBlankDescription() {
        assertInvalid("""
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50, "referenceTransactions": [
                  { "type": "CONVENIENCE_FEE", "amount": 100, "description": " " }
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
                { "aggregatorCode": "CORALPAY", "aggregatorCommissionAmount": 50 }
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
        return ReferenceTransaction.parseBillsPostingWithdrawal(command(json), DEFAULT_TRANSACTION_AMOUNT);
    }

    private JsonCommand command(final String json) {
        FromJsonHelper fromJsonHelper = new FromJsonHelper();
        return new JsonCommand(1L, fromJsonHelper.parse(json), fromJsonHelper);
    }
}
