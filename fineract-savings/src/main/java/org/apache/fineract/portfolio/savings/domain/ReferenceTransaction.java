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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;

/**
 * AB-265: side-effect transaction asserted by an upstream system (Synapse) to be created alongside a primary savings
 * transaction (deposit, withdrawal, transfer) inside the same DB transaction. Used to carry the EMT Levy row whose
 * threshold + direction + intra-client decision was made in Synapse.
 *
 * <p>
 * The receiver does not re-evaluate the rule — it just records what the caller asserts, linked to the parent via the
 * parent's {@code ref_no}. Forward-compatible with future siblings (VAT, etc.).
 */
public record ReferenceTransaction(SavingsAccountTransactionType type, BigDecimal amount, String description,
        CommissionBreakdown breakdown) {

    public ReferenceTransaction {
        if (type == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.type.required",
                    "Reference transaction type is required");
        }
        if (amount == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.amount.required",
                    "Reference transaction amount is required");
        }
        if (amount.signum() <= 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.amount.not.positive",
                    "Reference transaction amount must be positive: " + amount, amount);
        }
    }

    public boolean isNipFee() {
        return type.isCommission() || type.isVat();
    }

    public boolean isSupportedNipWithdrawalReference() {
        return type.isEmtLevy() || isNipFee();
    }

    public boolean hasNipWithdrawalNote() {
        return isNipFee() || (type.isEmtLevy() && StringUtils.isNotBlank(description));
    }

    /**
     * AB-339: only Commission is intrinsically NIP-switch-scoped and requires a {@code switchId}. VAT can also ride
     * standalone alongside a non-NIP principal (e.g. the signed e-statement fee), so it must not force a switch.
     */
    public boolean requiresSwitchId() {
        return type.isCommission();
    }

    /**
     * AB-510: Convenience Fee is intrinsically aggregator-scoped and always requires an {@code aggregatorCode}. VAT
     * never requires one — it resolves via the flat VAT_PAYABLE financial activity regardless of caller. The
     * aggregator's payable amount and commission no longer ride as reference-transaction legs at all — both are derived
     * on the primary withdrawal itself from its own {@code aggregatorCommissionAmount} field.
     */
    public boolean requiresAggregatorCode() {
        return type.isConvenienceFee();
    }

    /** Reference transaction types a bills/airtime posting (AB-510) is allowed to carry. */
    public boolean isBillsPostingFee() {
        return type.isConvenienceFee() || type.isVat();
    }

    public BigDecimal switchFeeAmount() {
        return breakdown == null || breakdown.switchFee() == null ? null : breakdown.switchFee().amount();
    }

    public BigDecimal bankCommissionAmount() {
        return breakdown == null || breakdown.bankCommission() == null ? null : breakdown.bankCommission().amount();
    }

    /**
     * AB-266: shared JSON-array parser for {@code referenceTransactions}-style fields. Deposit / withdrawal use
     * {@code "referenceTransactions"}; the account-transfer path uses {@code "sourceReferenceTransactions"} and
     * {@code "destinationReferenceTransactions"} so each leg's levy is applied against its own parent transaction.
     * Absent, {@code null}, or empty arrays yield an empty list — the caller skips the applier in that case.
     */
    public static List<ReferenceTransaction> parseArray(final JsonCommand command, final String paramName) {
        if (!command.parameterExists(paramName)) {
            return List.of();
        }
        final JsonArray array = command.arrayOfParameterNamed(paramName);
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        final List<ReferenceTransaction> refs = new ArrayList<>(array.size());
        for (final JsonElement element : array) {
            final JsonObject obj = element.getAsJsonObject();
            final String typeName = requiredString(obj, "type", paramName);
            final BigDecimal amount = requiredAmount(obj, "amount", paramName);
            final SavingsAccountTransactionType type;
            try {
                type = SavingsAccountTransactionType.valueOf(typeName);
            } catch (final IllegalArgumentException ex) {
                // Pass ex through defaultUserMessageArgs so AbstractPlatformException.findThrowableCause chains it
                // as the RuntimeException cause without violating checkstyle's AvoidHidingCauseException rule.
                throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.type.unknown",
                        "Unknown " + paramName + ".type: " + typeName, typeName, ex);
            }
            final CommissionBreakdown breakdown = type.isVat() ? null : parseBreakdown(obj, paramName);
            refs.add(new ReferenceTransaction(type, amount, optionalString(obj, "description"), breakdown));
        }
        return refs;
    }

    /** Parses and validates reference transactions accepted by an outbound NIP savings withdrawal. */
    public static NipWithdrawalRequest parseNipWithdrawal(final JsonCommand command) {
        final List<ReferenceTransaction> references = parseArray(command, "referenceTransactions");
        final boolean switchIdPresent = command.parameterExists("switchId");
        final String switchId = switchIdPresent ? normalizeSwitchId(command.stringValueOfParameterNamed("switchId")) : null;
        final boolean requiresSwitch = references.stream().anyMatch(ReferenceTransaction::requiresSwitchId);

        if (!switchIdPresent && requiresSwitch) {
            throw invalid("switchId.required", "switchId is required for Commission reference transactions");
        }
        if (!switchIdPresent) {
            return new NipWithdrawalRequest(null, references);
        }
        if (references.stream().anyMatch(reference -> !reference.isSupportedNipWithdrawalReference())) {
            throw invalid("reference.transaction.type.not.supported",
                    "NIP withdrawals support only EMT levy, Commission, and VAT reference transactions");
        }
        references.stream().filter(ReferenceTransaction::isNipFee).forEach(ReferenceTransaction::validateNipFee);
        return new NipWithdrawalRequest(switchId, references);
    }

    /**
     * Parses inbound NIP additions accepted by a savings deposit. Existing deposit requests without a switch retain
     * their original reference-transaction rules.
     */
    public static NipDepositRequest parseNipDeposit(final JsonCommand command) {
        final List<ReferenceTransaction> references = parseArray(command, "referenceTransactions");
        if (!command.parameterExists("switchId")) {
            rejectNipFields(command, references);
            return new NipDepositRequest(null, references);
        }

        final String switchId = normalizeSwitchId(command.stringValueOfParameterNamed("switchId"));
        if (references.stream().anyMatch(reference -> !reference.type().isEmtLevy())) {
            throw invalid("reference.transaction.type.not.supported", "Inbound NIP deposits support only EMT levy reference transactions");
        }
        return new NipDepositRequest(switchId, references);
    }

    /**
     * Parses the aggregator-scoped additions accepted by a bills/airtime savings withdrawal (AB-510). Mirrors
     * {@link #parseNipWithdrawal(JsonCommand)}, keyed on {@code aggregatorCode} instead of {@code switchId} — the two
     * are mutually exclusive on a single withdrawal request, so the caller (the write platform service) picks whichever
     * of {@code parseNipWithdrawal}/{@code parseBillsPostingWithdrawal} applies based on which top-level field is
     * present before falling back to the plain withdrawal path. Unlike NIP, a bills/airtime withdrawal also carries its
     * own {@code aggregatorCommissionAmount} — the split between the aggregator's payable amount and the bank's
     * commission is computed from the primary transaction itself, not from a separate reference leg.
     */
    public static BillsPostingWithdrawalRequest parseBillsPostingWithdrawal(final JsonCommand command, final BigDecimal transactionAmount) {
        final List<ReferenceTransaction> references = parseArray(command, "referenceTransactions");
        final boolean aggregatorCodePresent = command.parameterExists("aggregatorCode");
        final String aggregatorCode = aggregatorCodePresent ? normalizeAggregatorCode(command.stringValueOfParameterNamed("aggregatorCode"))
                : null;
        final boolean requiresAggregator = references.stream().anyMatch(ReferenceTransaction::requiresAggregatorCode);

        if (!aggregatorCodePresent && requiresAggregator) {
            throw invalid("aggregatorCode.required", "aggregatorCode is required for Convenience Fee reference transactions");
        }
        if (!aggregatorCodePresent) {
            return new BillsPostingWithdrawalRequest(null, null, references);
        }
        final BigDecimal commissionAmount = requireAggregatorCommissionAmount(command, transactionAmount);
        if (references.stream().anyMatch(reference -> !reference.isBillsPostingFee())) {
            throw invalid("reference.transaction.type.not.supported",
                    "Bills/airtime postings support only Convenience Fee and VAT reference transactions");
        }
        references.forEach(ReferenceTransaction::validateBillsPostingFee);
        return new BillsPostingWithdrawalRequest(aggregatorCode, commissionAmount, references);
    }

    private static BigDecimal requireAggregatorCommissionAmount(final JsonCommand command, final BigDecimal transactionAmount) {
        if (!command.parameterExists("aggregatorCommissionAmount")) {
            throw invalid("aggregatorCommissionAmount.required", "aggregatorCommissionAmount is required when aggregatorCode is present");
        }
        final BigDecimal commissionAmount = command.bigDecimalValueOfParameterNamed("aggregatorCommissionAmount");
        if (commissionAmount == null || commissionAmount.signum() < 0) {
            throw invalid("aggregatorCommissionAmount.not.negative", "aggregatorCommissionAmount must be zero or positive");
        }
        if (transactionAmount != null && commissionAmount.compareTo(transactionAmount) > 0) {
            throw invalid("aggregatorCommissionAmount.exceeds.transaction.amount",
                    "aggregatorCommissionAmount must not exceed the withdrawal transactionAmount");
        }
        return commissionAmount;
    }

    /** Rejects NIP-specific and bills/airtime-specific request data on transfer paths. */
    public static void rejectNipFields(final JsonCommand command, final List<ReferenceTransaction> references) {
        if (command.parameterExists("switchId") || command.parameterExists("aggregatorCode")
                || references.stream().anyMatch(reference -> reference.isNipFee() || reference.type().isAggregatorPayable()
                        || reference.type().isConvenienceFee() || reference.description() != null || reference.breakdown() != null)) {
            throw invalid("not.supported", "NIP and bills/airtime request fields are supported only on savings withdrawals");
        }
    }

    private void validateNipFee() {
        if (StringUtils.isBlank(description)) {
            throw invalid("reference.transaction.description.required", "NIP reference transaction description is required");
        }
        if (type.isCommission()) {
            if (breakdown == null) {
                throw invalid("commission.breakdown.required", "Commission reference transaction breakdown is required");
            }
            breakdown.validate(amount);
        }
    }

    private static String normalizeSwitchId(final String switchId) {
        if (StringUtils.isBlank(switchId)) {
            throw invalid("switchId.required", "switchId must be nonblank");
        }
        return switchId.trim().toUpperCase(Locale.ROOT);
    }

    private void validateBillsPostingFee() {
        if (StringUtils.isBlank(description)) {
            throw invalid("reference.transaction.description.required", "Bills/airtime reference transaction description is required");
        }
    }

    private static String normalizeAggregatorCode(final String aggregatorCode) {
        if (StringUtils.isBlank(aggregatorCode)) {
            throw invalid("aggregatorCode.required", "aggregatorCode must be nonblank");
        }
        return aggregatorCode.trim().toUpperCase(Locale.ROOT);
    }

    private static CommissionBreakdown parseBreakdown(final JsonObject reference, final String paramName) {
        if (!reference.has("breakdown") || reference.get("breakdown").isJsonNull()) {
            return null;
        }
        final JsonObject breakdown = reference.getAsJsonObject("breakdown");
        return new CommissionBreakdown(parseLeg(breakdown, "switchFee", paramName), parseLeg(breakdown, "bankCommission", paramName));
    }

    private static CommissionBreakdownLeg parseLeg(final JsonObject breakdown, final String name, final String paramName) {
        if (!breakdown.has(name) || breakdown.get(name).isJsonNull()) {
            return null;
        }
        final JsonObject leg = breakdown.getAsJsonObject(name);
        return new CommissionBreakdownLeg(
                leg.has("amount") && !leg.get("amount").isJsonNull() ? leg.get("amount").getAsBigDecimal() : null);
    }

    private static String requiredString(final JsonObject object, final String name, final String paramName) {
        final String value = optionalString(object, name);
        if (StringUtils.isBlank(value)) {
            throw invalid("reference.transaction." + name + ".required", paramName + "." + name + " is required");
        }
        return value;
    }

    private static String optionalString(final JsonObject object, final String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
    }

    private static BigDecimal requiredAmount(final JsonObject object, final String name, final String paramName) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw invalid("reference.transaction." + name + ".required", paramName + "." + name + " is required");
        }
        return object.get(name).getAsBigDecimal();
    }

    private static GeneralPlatformDomainRuleException invalid(final String code, final String message) {
        return new GeneralPlatformDomainRuleException("error.msg.savings.nip." + code, message);
    }

    public record NipWithdrawalRequest(String switchId, List<ReferenceTransaction> references) {
    }

    public record NipDepositRequest(String switchId, List<ReferenceTransaction> references) {
    }

    public record BillsPostingWithdrawalRequest(String aggregatorCode, BigDecimal commissionAmount, List<ReferenceTransaction> references) {
    }

    public record CommissionBreakdown(CommissionBreakdownLeg switchFee, CommissionBreakdownLeg bankCommission) {

        private void validate(final BigDecimal parentAmount) {
            if (switchFee == null || bankCommission == null || switchFee.amount == null || bankCommission.amount == null) {
                throw invalid("commission.breakdown.legs.required",
                        "Commission breakdown switchFee and bankCommission amounts are required");
            }
            if (switchFee.amount.signum() < 0 || bankCommission.amount.signum() < 0) {
                throw invalid("commission.breakdown.amount.not.negative", "Commission breakdown amounts must be non-negative");
            }
            if (switchFee.amount.add(bankCommission.amount).compareTo(parentAmount) != 0) {
                throw invalid("commission.breakdown.sum.mismatch", "Commission breakdown amounts must equal the Commission amount");
            }
        }
    }

    public record CommissionBreakdownLeg(BigDecimal amount) {
    }
}
