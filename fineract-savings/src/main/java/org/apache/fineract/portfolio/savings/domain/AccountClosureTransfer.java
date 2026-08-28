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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;

/**
 * AB-414: a withdrawal that zeroes an account and closes it in the same database transaction.
 *
 * <p>
 * Synapse debits TigerBeetle synchronously but reaches Fineract asynchronously over Kafka, so it cannot close an
 * account with a second call — Fineract refuses to close while its own balance is non-zero, and the sweep may not have
 * landed yet. Instead the closing account's debit leg carries {@code isAccountClosureTransfer: true} plus a nested
 * {@code closure} object holding the ordinary savings-close payload. Fineract asserts the debit equals the whole
 * balance, posts it, then runs its normal close — one atomic unit.
 *
 * <p>
 * The balance assertion is what makes retries safe. Any earlier queued transaction that has not yet been applied makes
 * the amounts disagree, so the closure is rejected as retriable and simply waits rather than closing on a stale
 * balance. The destination credit is an independent leg of the same Synapse batch and posts on its own.
 */
public record AccountClosureTransfer(boolean requested, JsonElement closurePayload) {

    private static final AccountClosureTransfer NOT_REQUESTED = new AccountClosureTransfer(false, null);

    /**
     * Raised when the debit does not equal the account's current balance. Synapse treats this code as retriable — it
     * usually means a queued transaction for the same account has not been applied yet.
     */
    public static final String BALANCE_MISMATCH_CODE = "error.msg.savings.account.closure.balance.mismatch";

    public static AccountClosureTransfer parse(final JsonCommand command) {
        if (!command.booleanPrimitiveValueOfParameterNamed(SavingsApiConstants.isAccountClosureTransferParamName)) {
            return NOT_REQUESTED;
        }

        final JsonElement parsed = command.parsedJson();
        if (parsed == null || !parsed.isJsonObject()) {
            throw invalid("closure.payload.required", "An account closure transfer requires a closure payload");
        }

        final JsonElement closure = parsed.getAsJsonObject().get(SavingsApiConstants.closureParamName);
        if (closure == null || !closure.isJsonObject()) {
            throw invalid("closure.payload.required",
                    "An account closure transfer requires a '" + SavingsApiConstants.closureParamName + "' object");
        }

        final JsonObject payload = closure.getAsJsonObject();
        if (!payload.has(SavingsApiConstants.closedOnDateParamName)) {
            throw invalid("closure.closedOnDate.required",
                    "An account closure transfer requires '" + SavingsApiConstants.closedOnDateParamName + "'");
        }

        return new AccountClosureTransfer(true, payload);
    }

    /**
     * The closing debit must retire the entire balance — Fineract's own close then sees zero and proceeds. Compared by
     * value so a scale difference (100 vs 100.00) is not treated as a mismatch.
     */
    public void assertClearsBalance(final BigDecimal transactionAmount, final BigDecimal accountBalance) {
        final BigDecimal amount = transactionAmount == null ? BigDecimal.ZERO : transactionAmount;
        final BigDecimal balance = accountBalance == null ? BigDecimal.ZERO : accountBalance;
        if (amount.compareTo(balance) != 0) {
            throw new GeneralPlatformDomainRuleException(BALANCE_MISMATCH_CODE,
                    "Account closure transfer of " + amount + " does not match the current balance of " + balance, amount, balance);
        }
    }

    /** The nested payload as a command the ordinary savings-close path can consume unchanged. */
    public JsonCommand closureCommand(final JsonCommand command) {
        return JsonCommand.fromExistingCommand(command, closurePayload);
    }

    private static GeneralPlatformDomainRuleException invalid(final String code, final String message) {
        return new GeneralPlatformDomainRuleException("error.msg.savings." + code, message);
    }
}
