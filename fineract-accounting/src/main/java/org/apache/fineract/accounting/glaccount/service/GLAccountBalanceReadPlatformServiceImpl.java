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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.common.AccountingEnumerations;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceData;
import org.apache.fineract.accounting.glaccount.data.GLAccountDetailsData;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.glaccount.domain.GLAccountUsage;
import org.apache.fineract.accounting.glaccount.exception.GLAccountMultipleCurrenciesException;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GLAccountBalanceReadPlatformServiceImpl implements GLAccountBalanceReadPlatformService {

    private static final String VALIDATION_ERRORS_EXIST = "validation.msg.validation.errors.exist";
    private static final String VALIDATION_ERROR_MESSAGE = "Validation errors exist.";

    /** {@code acc_gl_journal_entry.type_enum}: see {@code JournalEntryType}. */
    private static final int ENTRY_TYPE_CREDIT = 1;
    private static final int ENTRY_TYPE_DEBIT = 2;

    /**
     * Excludes both legs of a reversal: {@code reversed = true} marks the original entry a reversal was posted against,
     * and the {@code not exists} clause excludes the reversal entry itself, which is the row some other entry's
     * {@code reversal_id} points at. A reversal is a deliberate correction — "this posting should not have happened" —
     * so ignoring it entirely reflects what actually stands, rather than summing a cancelling pair that happens to net
     * to the same total.
     */
    private static final String EXCLUDE_REVERSED = """
            and je.reversed = false
            and not exists (select 1 from acc_gl_journal_entry rev where rev.reversal_id = je.id)""";

    private static final String GL_ACCOUNT_SQL = """
            select gl.id                             as id,
                   gl.gl_code                        as glCode,
                   gl.name                           as name,
                   gl.classification_enum            as classification,
                   gl.account_usage                  as accountUsage,
                   gl.disabled                       as disabled,
                   gl.manual_journal_entries_allowed as manualEntriesAllowed,
                   gl.description                    as description,
                   parent.id                         as parentId,
                   parent.gl_code                    as parentGlCode,
                   parent.name                       as parentName
            from acc_gl_account gl
            left join acc_gl_account parent on parent.id = gl.parent_id
            where gl.gl_code = :glCode""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public GLAccountDetailsData retrieveGLAccountDetailsByCode(final String glCode, final LocalDate asOnDate, final Long officeId,
            final String currencyCode) {
        final GLAccountRow account = findAccountByCode(glCode);
        final LocalDate cutOff = asOnDate == null ? DateUtils.getBusinessLocalDate() : asOnDate;

        final CumulativeRow cumulative = retrieveCumulative(account.id(), cutOff, officeId, currencyCode);
        final String currency = resolveCurrency(glCode, account.id(), cutOff, officeId, currencyCode);

        return new GLAccountDetailsData(account.id(), account.glCode(), account.name(),
                AccountingEnumerations.gLAccountType(account.type()), AccountingEnumerations.gLAccountUsage(account.usage()),
                account.disabled(), account.manualEntriesAllowed(), account.description(), account.parentId(), account.parentGlCode(),
                account.parentName(), cutOff,
                GLAccountBalanceCalculator.signedNet(account.type(), cumulative.totalDebits(), cumulative.totalCredits()),
                GLAccountBalanceCalculator.money(cumulative.totalDebits()), GLAccountBalanceCalculator.money(cumulative.totalCredits()),
                cumulative.lastMovementDate(), currency, cumulative.entryCount(), officeId);
    }

    @Override
    public GLAccountBalanceData retrieveGLAccountBalanceByCode(final String glCode, final LocalDate fromDate, final LocalDate toDate,
            final Long officeId, final String currencyCode) {
        final GLAccountRow account = findAccountByCode(glCode);
        validateWindow(fromDate, toDate);

        final WindowRow window = retrieveWindow(account.id(), fromDate, toDate, officeId, currencyCode);
        final String currency = resolveCurrency(glCode, account.id(), toDate, officeId, currencyCode);

        final BigDecimal opening = GLAccountBalanceCalculator.signedNet(account.type(), window.openingDebits(), window.openingCredits());
        final BigDecimal net = GLAccountBalanceCalculator.signedNet(account.type(), window.totalDebits(), window.totalCredits());

        return new GLAccountBalanceData(account.id(), account.glCode(), account.name(),
                AccountingEnumerations.gLAccountType(account.type()), officeId, currency, fromDate, toDate, opening,
                GLAccountBalanceCalculator.money(window.totalDebits()), GLAccountBalanceCalculator.money(window.totalCredits()), net,
                opening.add(net), window.entryCount());
    }

    // -------------------------------------------------------------------------------------------- account master data

    private GLAccountRow findAccountByCode(final String glCode) {
        try {
            return this.jdbcTemplate.queryForObject(GL_ACCOUNT_SQL, Map.of("glCode", glCode), new GLAccountRowMapper());
        } catch (final EmptyResultDataAccessException e) {
            throw new GLAccountNotFoundException(glCode, e);
        }
    }

    private record GLAccountRow(Long id, String glCode, String name, GLAccountType type, GLAccountUsage usage, Boolean disabled,
            Boolean manualEntriesAllowed, String description, Long parentId, String parentGlCode, String parentName) {
    }

    private static final class GLAccountRowMapper implements RowMapper<GLAccountRow> {

        @Override
        public GLAccountRow mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            // classification_enum and account_usage are both NOT NULL, so a plain getInt is exact here.
            return new GLAccountRow(rs.getLong("id"), rs.getString("glCode"), rs.getString("name"),
                    GLAccountType.fromInt(rs.getInt("classification")), GLAccountUsage.fromInt(rs.getInt("accountUsage")),
                    rs.getBoolean("disabled"), rs.getBoolean("manualEntriesAllowed"), rs.getString("description"),
                    rs.getObject("parentId", Long.class), rs.getString("parentGlCode"), rs.getString("parentName"));
        }
    }

    // ------------------------------------------------------------------------------------------- cumulative aggregate

    /**
     * One query for the as-of balance basis plus the true last movement date. The date cut-off sits inside each
     * aggregate's {@code case} rather than in the {@code where} clause so {@code max(entry_date)} stays uncapped — a
     * forward-dated posting must still be reported as the latest movement even though it is excluded from the balance.
     */
    private CumulativeRow retrieveCumulative(final Long accountId, final LocalDate asOnDate, final Long officeId,
            final String currencyCode) {
        final Map<String, Object> params = new HashMap<>();
        params.put("accountId", accountId);
        params.put("asOnDate", asOnDate);

        final StringBuilder sql = new StringBuilder(" select ").append(asOfSumOf(ENTRY_TYPE_DEBIT)).append(" as totalDebits, ")
                .append(asOfSumOf(ENTRY_TYPE_CREDIT)).append(" as totalCredits, ")
                .append(" count(case when je.entry_date <= :asOnDate then 1 end) as entryCount, ")
                .append(" max(je.entry_date) as lastMovementDate ").append(" from acc_gl_journal_entry je ")
                .append(" where je.account_id = :accountId ").append(EXCLUDE_REVERSED);
        appendScopeFilters(sql, params, officeId, currencyCode);

        return this.jdbcTemplate.queryForObject(sql.toString(), params, (rs, rowNum) -> new CumulativeRow(rs.getBigDecimal("totalDebits"),
                rs.getBigDecimal("totalCredits"), rs.getLong("entryCount"), JdbcSupport.getLocalDate(rs, "lastMovementDate")));
    }

    private record CumulativeRow(BigDecimal totalDebits, BigDecimal totalCredits, Long entryCount, LocalDate lastMovementDate) {
    }

    // ------------------------------------------------------------------------------------------------ window movement

    /**
     * Opening basis and window totals from a single query with no {@code group by}: the opening side sums entries
     * strictly before {@code fromDate}, the window side sums entries in {@code [fromDate, toDate]}, and both run over
     * the same scan of {@code acc_gl_journal_entry} for the account.
     */
    private WindowRow retrieveWindow(final Long accountId, final LocalDate fromDate, final LocalDate toDate, final Long officeId,
            final String currencyCode) {
        final Map<String, Object> params = new HashMap<>();
        params.put("accountId", accountId);
        params.put("fromDate", fromDate);
        params.put("toDate", toDate);

        final StringBuilder sql = new StringBuilder(" select ").append(openingSumOf(ENTRY_TYPE_DEBIT)).append(" as openingDebits, ")
                .append(openingSumOf(ENTRY_TYPE_CREDIT)).append(" as openingCredits, ").append(windowSumOf(ENTRY_TYPE_DEBIT))
                .append(" as totalDebits, ").append(windowSumOf(ENTRY_TYPE_CREDIT)).append(" as totalCredits, ")
                .append(" count(case when je.entry_date between :fromDate and :toDate then 1 end) as entryCount ")
                .append(" from acc_gl_journal_entry je ").append(" where je.account_id = :accountId ")
                .append(" and je.entry_date <= :toDate ").append(EXCLUDE_REVERSED);
        appendScopeFilters(sql, params, officeId, currencyCode);

        return this.jdbcTemplate.queryForObject(sql.toString(), params,
                (rs, rowNum) -> new WindowRow(rs.getBigDecimal("openingDebits"), rs.getBigDecimal("openingCredits"),
                        rs.getBigDecimal("totalDebits"), rs.getBigDecimal("totalCredits"), rs.getLong("entryCount")));
    }

    private record WindowRow(BigDecimal openingDebits, BigDecimal openingCredits, BigDecimal totalDebits, BigDecimal totalCredits,
            Long entryCount) {
    }

    // --------------------------------------------------------------------------------------------- currency resolution

    /**
     * Fineract does not model a currency on a GL account, so it is derived from what has actually been posted — always
     * over the account's <em>entire</em> history up to {@code windowEnd}, never scoped to a narrower request window. A
     * balance request over a quiet window (no entries between its own {@code fromDate} and {@code toDate}) must still
     * resolve to the account's real, established currency rather than {@code null}: the opening balance it is reported
     * alongside is itself computed from that same unbounded history, so scoping currency to the request window would
     * report a nonzero balance with no currency to name it in.
     *
     * @param windowEnd
     *            the as-of date, or the inclusive window end
     */
    private String resolveCurrency(final String glCode, final Long accountId, final LocalDate windowEnd, final Long officeId,
            final String currencyCodeFilter) {
        final String filter = trimToNull(currencyCodeFilter);
        if (filter != null) {
            return filter;
        }

        final List<String> distinct = retrieveDistinctCurrencies(accountId, windowEnd, officeId);
        if (distinct.isEmpty()) {
            return null;
        }
        if (distinct.size() == 1) {
            return distinct.get(0);
        }
        throw new GLAccountMultipleCurrenciesException(glCode);
    }

    private List<String> retrieveDistinctCurrencies(final Long accountId, final LocalDate windowEnd, final Long officeId) {
        final Map<String, Object> params = new HashMap<>();
        params.put("accountId", accountId);
        params.put("windowEnd", windowEnd);

        final StringBuilder sql = new StringBuilder(" select distinct je.currency_code as currencyCode ")
                .append(" from acc_gl_journal_entry je ").append(" where je.account_id = :accountId ")
                .append(" and je.entry_date <= :windowEnd ").append(EXCLUDE_REVERSED);
        appendScopeFilters(sql, params, officeId, null);
        sql.append(" order by je.currency_code");

        return this.jdbcTemplate.queryForList(sql.toString(), params, String.class);
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    /** Sum of one posting side over the whole filtered set. */
    private static String windowSumOf(final int entryType) {
        return " coalesce(sum(case when je.entry_date between :fromDate and :toDate and je.type_enum = " + entryType
                + " then je.amount else 0 end), 0) ";
    }

    /** Same, restricted to entries strictly before {@code :fromDate} — the opening-balance basis. */
    private static String openingSumOf(final int entryType) {
        return " coalesce(sum(case when je.entry_date < :fromDate and je.type_enum = " + entryType + " then je.amount else 0 end), 0) ";
    }

    /** Restricted to entries on or before {@code :asOnDate}, so the cut-off does not cap {@code max(entry_date)}. */
    private static String asOfSumOf(final int entryType) {
        return " coalesce(sum(case when je.entry_date <= :asOnDate and je.type_enum = " + entryType + " then je.amount else 0 end), 0) ";
    }

    /**
     * Appends office and currency predicates only when they are actually filtering. Binding {@code :officeId is null}
     * instead would fail on PostgreSQL with "could not determine data type of parameter" and would need a database
     * -specific cast to work on MySQL.
     */
    private static void appendScopeFilters(final StringBuilder sql, final Map<String, Object> params, final Long officeId,
            final String currencyCode) {
        if (officeId != null) {
            sql.append(" and je.office_id = :officeId");
            params.put("officeId", officeId);
        }
        final String currency = trimToNull(currencyCode);
        if (currency != null) {
            sql.append(" and je.currency_code = :currencyCode");
            params.put("currencyCode", currency);
        }
    }

    private static void validateWindow(final LocalDate fromDate, final LocalDate toDate) {
        if (fromDate == null) {
            throw validationError("validation.msg.glaccount.balance.fromDate.required", "The parameter `fromDate` is required.",
                    "fromDate");
        }
        if (toDate == null) {
            throw validationError("validation.msg.glaccount.balance.toDate.required", "The parameter `toDate` is required.", "toDate");
        }
        if (DateUtils.isAfter(fromDate, toDate)) {
            throw validationError("validation.msg.glaccount.balance.fromDate.after.toDate",
                    "The parameter `fromDate` must not be after `toDate`.", "fromDate", fromDate, toDate);
        }
    }

    private static PlatformApiDataValidationException validationError(final String code, final String message, final String parameterName,
            final Object... args) {
        final ApiParameterError error = ApiParameterError.parameterError(code, message, parameterName, args);
        return new PlatformApiDataValidationException(VALIDATION_ERRORS_EXIST, VALIDATION_ERROR_MESSAGE, List.of(error));
    }

    private static String trimToNull(final String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }
}
