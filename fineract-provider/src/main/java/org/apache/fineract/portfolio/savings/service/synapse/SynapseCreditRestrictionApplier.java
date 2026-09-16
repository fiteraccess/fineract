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

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;

/**
 * Replays Synapse's credit-restriction decision onto a savings account's inert flag. Idempotent on the target value: a
 * redelivered command finds the flag already set and writes nothing.
 *
 * <p>
 * The flag is read by the interest engine only (see {@code SavingsAccount#isSynapseCreditRestricted()}), so applying it
 * involves no validation, no transaction and no journal entry — unlike the dormancy replay it sits beside.
 */
@Slf4j
@RequiredArgsConstructor
public class SynapseCreditRestrictionApplier {

    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;

    public record ApplyResult(boolean restricted, boolean alreadyApplied) {
    }

    public static final String PARAM_RESTRICTED = "restricted";

    /**
     * The decision must be stated: an absent or null {@code restricted} is refused rather than read as {@code false},
     * because the primitive default would turn an empty replay into a lift.
     */
    public static boolean requiredRestricted(final JsonCommand command) {
        final Boolean restricted = command.parameterExists(PARAM_RESTRICTED) ? command.booleanObjectValueOfParameterNamed(PARAM_RESTRICTED)
                : null;
        if (restricted == null) {
            throw new PlatformApiDataValidationException(
                    List.of(ApiParameterError.parameterError("error.msg.savingsaccount.credit.restriction.restricted.required",
                            "The credit restriction replay must state restricted=true or restricted=false", PARAM_RESTRICTED)));
        }
        return restricted;
    }

    public ApplyResult apply(final SavingsAccount account, final boolean restricted) {
        if (account.isSynapseCreditRestricted() == restricted) {
            log.debug("Credit-restriction replay no-op: account={} already restricted={}", account.getId(), restricted);
            return new ApplyResult(restricted, true);
        }
        account.setSynapseCreditRestricted(restricted);
        savingsAccountRepositoryWrapper.saveAndFlush(account);
        return new ApplyResult(restricted, false);
    }
}
