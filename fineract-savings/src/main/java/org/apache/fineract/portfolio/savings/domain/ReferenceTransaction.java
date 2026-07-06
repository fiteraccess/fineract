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
public record ReferenceTransaction(SavingsAccountTransactionType type, BigDecimal amount) {

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
            final String typeName = obj.get("type").getAsString();
            final BigDecimal amount = obj.get("amount").getAsBigDecimal();
            final SavingsAccountTransactionType type;
            try {
                type = SavingsAccountTransactionType.valueOf(typeName);
            } catch (final IllegalArgumentException ex) {
                // Pass ex through defaultUserMessageArgs so AbstractPlatformException.findThrowableCause chains it
                // as the RuntimeException cause without violating checkstyle's AvoidHidingCauseException rule.
                throw new GeneralPlatformDomainRuleException("error.msg.savings.reference.transaction.type.unknown",
                        "Unknown " + paramName + ".type: " + typeName, typeName, ex);
            }
            refs.add(new ReferenceTransaction(type, amount));
        }
        return refs;
    }
}
