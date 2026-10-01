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
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccruedInterestData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SavingsAccruedInterestReadService {

    private static final String ACCOUNT_SQL = "select sa.id, sa.account_no, sa.status_enum, sa.currency_code, sa.currency_digits,"
            + " sa.activatedon_date, sa.interest_posted_till_date from m_savings_account sa where sa.id = ?";
    private static final String SUMS_SQL = "select"
            + " coalesce(sum(case when tr.transaction_type_enum = ? and tr.transaction_date >= ? and tr.transaction_date <= ?"
            + " then tr.amount end), 0) as accrued,"
            + " max(case when tr.transaction_type_enum = ? and tr.transaction_date >= ? and tr.transaction_date <= ?"
            + " then tr.transaction_date end) as last_accrual,"
            + " coalesce(sum(case when tr.transaction_type_enum = ? then tr.amount end), 0) as posted"
            + " from m_savings_account_transaction tr"
            + " where tr.savings_account_id = ? and tr.is_reversed = false and tr.is_reversal = false";

    private final JdbcTemplate jdbcTemplate;

    public SavingsAccruedInterestData retrieve(final Long accountId) {
        final List<AccountRow> accounts = jdbcTemplate.query(ACCOUNT_SQL,
                (rs, rowNum) -> new AccountRow(rs.getLong("id"), rs.getString("account_no"), JdbcSupport.getInteger(rs, "status_enum"),
                        rs.getString("currency_code"), JdbcSupport.getInteger(rs, "currency_digits"),
                        JdbcSupport.getLocalDate(rs, "activatedon_date"), JdbcSupport.getLocalDate(rs, "interest_posted_till_date")),
                accountId);
        if (accounts.isEmpty()) {
            throw new SavingsAccountNotFoundException(accountId);
        }
        final AccountRow account = accounts.get(0);
        if (SavingsAccountStatusType.fromInt(account.statusId()).isClosed()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.closed",
                    "Savings account " + account.accountNo() + " is closed", account.accountNo());
        }

        // Accruals are dated at their period end, so the open period starts the day after the last posting.
        final LocalDate periodStart = account.postedTill() != null ? account.postedTill().plusDays(1) : account.activation();
        final LocalDate today = DateUtils.getBusinessLocalDate();
        final int accrual = SavingsAccountTransactionType.ACCRUAL.getValue();
        if (periodStart == null) {
            return toData(account, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        }
        return jdbcTemplate.queryForObject(SUMS_SQL,
                (rs, rowNum) -> toData(account, rs.getBigDecimal("accrued"), rs.getBigDecimal("posted"), periodStart,
                        JdbcSupport.getLocalDate(rs, "last_accrual")),
                accrual, periodStart, today, accrual, periodStart, today, SavingsAccountTransactionType.INTEREST_POSTING.getValue(),
                accountId);
    }

    private static SavingsAccruedInterestData toData(final AccountRow account, final BigDecimal accrued, final BigDecimal posted,
            final LocalDate periodStart, final LocalDate periodEnd) {
        return new SavingsAccruedInterestData(account.id(), account.accountNo(), account.statusId(), account.currencyCode(),
                account.currencyDigits(), accrued, posted, periodStart, periodEnd, account.postedTill(), account.activation());
    }

    private record AccountRow(Long id, String accountNo, Integer statusId, String currencyCode, Integer currencyDigits,
            LocalDate activation, LocalDate postedTill) {
    }
}
