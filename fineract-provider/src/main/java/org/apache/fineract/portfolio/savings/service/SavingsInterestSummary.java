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
package org.apache.fineract.portfolio.savings.service;

import java.math.BigDecimal;
import org.apache.fineract.accounting.common.AccountingRuleType;

/**
 * AB-401: the stored earned total covers only the last calculation's window, so earned minus posted went negative; a
 * periodic-accrual account's own accruals say what is owed instead.
 */
record SavingsInterestSummary(BigDecimal earned, BigDecimal notPosted) {

    static SavingsInterestSummary of(final Integer accountingType, final boolean goal, final BigDecimal storedEarned,
            final BigDecimal posted, final BigDecimal overdraftInterest, final BigDecimal accruedSinceLastPosting) {
        if (!AccountingRuleType.ACCRUAL_PERIODIC.getValue().equals(accountingType)) {
            final BigDecimal notPosted = storedEarned == null ? BigDecimal.ZERO : storedEarned.subtract(posted).add(overdraftInterest);
            return new SavingsInterestSummary(storedEarned, notPosted);
        }
        final BigDecimal accrued = accruedSinceLastPosting == null ? BigDecimal.ZERO : accruedSinceLastPosting;
        final BigDecimal notPosted = SavingsAccruedInterestReadService.owed(goal, accrued, posted);
        final BigDecimal earned = posted.add(notPosted);
        return new SavingsInterestSummary(earned.signum() == 0 ? null : earned, notPosted);
    }
}
