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
package org.apache.fineract.accounting.glaccount.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;

/**
 * The GL balance sign rule, in one place.
 *
 * <p>
 * This is a second implementation of the rule already encoded in
 * {@code JournalEntryRunningBalanceUpdateServiceImpl.calculateRunningBalance}, which decides per-entry whether the
 * entry increases or decreases the account. That method walks entries one at a time and cannot be reused for an
 * aggregate, so the rule is restated here over summed debits and credits. The two must never disagree —
 * {@code GLAccountBalanceCalculatorTest.matchesFineractRunningBalanceRule} pins them together over every (account type,
 * entry type) pair.
 */
public final class GLAccountBalanceCalculator {

    /**
     * {@code acc_gl_journal_entry.amount} is {@code decimal(19,6)}, so every figure derived from it is normalised to
     * the same scale. Without this, a bucket with no rows reports {@code 0} next to a sibling reporting
     * {@code 1234.500000}.
     */
    public static final int MONEY_SCALE = 6;

    private GLAccountBalanceCalculator() {}

    /**
     * The signed movement of {@code debits} and {@code credits} against an account of this type: ASSET and EXPENSE
     * accounts increase on debit, LIABILITY, EQUITY and INCOME increase on credit.
     *
     * <p>
     * The switch is exhaustive with no {@code default} on purpose. If a sixth {@link GLAccountType} is ever added
     * upstream this stops compiling, rather than silently mis-signing money for the new type.
     */
    public static BigDecimal signedNet(final GLAccountType type, final BigDecimal debits, final BigDecimal credits) {
        final BigDecimal debit = money(debits);
        final BigDecimal credit = money(credits);
        return switch (type) {
            case ASSET, EXPENSE -> debit.subtract(credit);
            case LIABILITY, EQUITY, INCOME -> credit.subtract(debit);
        };
    }

    /**
     * Normalises to the column's scale. {@link RoundingMode#UNNECESSARY} is deliberate: every input is either a sum of
     * {@code decimal(19,6)} values or a difference of two such sums, so no rounding can legitimately be required.
     * Raising the scale of a smaller-scale value (a bare {@code 0} from {@code COALESCE}) never rounds either. A throw
     * here would mean the column definition had changed underneath us, which is worth knowing loudly.
     */
    public static BigDecimal money(final BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}
