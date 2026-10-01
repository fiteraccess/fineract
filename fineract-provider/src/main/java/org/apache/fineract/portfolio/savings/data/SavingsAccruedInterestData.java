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
package org.apache.fineract.portfolio.savings.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interest accrued on a savings account but not yet posted, read from its periodic ACCRUAL transactions.
 *
 * @param accruedAmount
 *            sum of non-reversed ACCRUAL transactions dated from {@code periodStart} to today
 * @param postedInterest
 *            sum of non-reversed interest postings since activation; for a goal this is interest held from settlements
 * @param periodStart
 *            day after {@code interestPostedTillDate}, or the activation date when interest was never posted
 * @param periodEnd
 *            date of the latest counted accrual, null when nothing has accrued yet
 */
public record SavingsAccruedInterestData(Long accountId, String accountNo, Integer statusId, String currencyCode,
        Integer currencyDecimalPlaces, BigDecimal accruedAmount, BigDecimal postedInterest, LocalDate periodStart, LocalDate periodEnd,
        LocalDate interestPostedTillDate, LocalDate activationDate) {
}
