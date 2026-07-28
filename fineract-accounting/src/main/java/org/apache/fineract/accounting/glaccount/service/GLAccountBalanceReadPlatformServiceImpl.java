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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.common.AccountingEnumerations;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceBucketData;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceData;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceGranularity;
import org.apache.fineract.accounting.glaccount.data.GLAccountDetailsData;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.glaccount.domain.GLAccountUsage;
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

    /**
     * Guardrail on {@code granularity=DAILY}. A little over a year, so a full twelve-month daily report works, while
     * {@code fromDate=1970-01-01} does not turn into a scan-and-serialise of twenty thousand buckets.
     */
    public static final int MAX_DAILY_BUCKETS = 400;

    private static final String VALIDATION_ERRORS_EXIST = "validation.msg.validation.errors.exist";
    private static final String VALIDATION_ERROR_MESSAGE = "Validation errors exist.";

    /** {@code acc_gl_journal_entry.type_enum}: see {@code JournalEntryType}. */
    private static final int ENTRY_TYPE_CREDIT = 1;
    private static final int ENTRY_TYPE_DEBIT = 2;

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
        final List<String> currencies = retrieveCurrencies(account.id(), cutOff, officeId);

        return new GLAccountDetailsData(account.id(), account.glCode(), account.name(),
                AccountingEnumerations.gLAccountType(account.type()), AccountingEnumerations.gLAccountUsage(account.usage()),
                account.disabled(), account.manualEntriesAllowed(), account.description(), account.parentId(), account.parentGlCode(),
                account.parentName(), cutOff,
                GLAccountBalanceCalculator.signedNet(account.type(), cumulative.totalDebits(), cumulative.totalCredits()),
                GLAccountBalanceCalculator.money(cumulative.totalDebits()), GLAccountBalanceCalculator.money(cumulative.totalCredits()),
                cumulative.lastMovementDate(), currencies, cumulative.entryCount(), officeId, trimToNull(currencyCode));
    }

    @Override
    public GLAccountBalanceData retrieveGLAccountBalanceByCode(final String glCode, final LocalDate fromDate, final LocalDate toDate,
            final GLAccountBalanceGranularity granularity, final Long officeId, final String currencyCode) {
        final GLAccountRow account = findAccountByCode(glCode);

        final LocalDate windowEnd = toDate == null ? DateUtils.getBusinessLocalDate() : toDate;
        final LocalDate windowStart = fromDate == null ? windowEnd : fromDate;
        validateWindow(windowStart, windowEnd, granularity);

        final List<MovementRow> rows = retrieveMovements(account.id(), windowStart, windowEnd, officeId, currencyCode);

        return new GLAccountBalanceData(account.id(), account.glCode(), account.name(),
                AccountingEnumerations.gLAccountType(account.type()), officeId, trimToNull(currencyCode), windowStart, windowEnd,
                granularity, openingBalance(account.type(), rows), buckets(granularity, windowStart, windowEnd, rows));
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
                .append(" where je.account_id = :accountId");
        appendScopeFilters(sql, params, officeId, currencyCode);

        return this.jdbcTemplate.queryForObject(sql.toString(), params, (rs, rowNum) -> new CumulativeRow(rs.getBigDecimal("totalDebits"),
                rs.getBigDecimal("totalCredits"), rs.getLong("entryCount"), JdbcSupport.getLocalDate(rs, "lastMovementDate")));
    }

    private record CumulativeRow(BigDecimal totalDebits, BigDecimal totalCredits, Long entryCount, LocalDate lastMovementDate) {
    }

    private List<String> retrieveCurrencies(final Long accountId, final LocalDate asOnDate, final Long officeId) {
        final Map<String, Object> params = new HashMap<>();
        params.put("accountId", accountId);
        params.put("asOnDate", asOnDate);

        final StringBuilder sql = new StringBuilder("""
                select distinct je.currency_code as currencyCode
                from acc_gl_journal_entry je
                where je.account_id = :accountId
                  and je.entry_date <= :asOnDate""");
        appendScopeFilters(sql, params, officeId, null);
        sql.append(" order by je.currency_code");

        return this.jdbcTemplate.queryForList(sql.toString(), params, String.class);
    }

    // ------------------------------------------------------------------------------------------------ window movement

    /**
     * Opening balance and every in-window bucket from a single grouped statement. Entries before the window collapse
     * into one {@code null}-keyed group, which is exactly the opening-balance basis, so no second round trip and no
     * dependency on the once-daily {@code organization_running_balance} column.
     */
    private List<MovementRow> retrieveMovements(final Long accountId, final LocalDate fromDate, final LocalDate toDate, final Long officeId,
            final String currencyCode) {
        final Map<String, Object> params = new HashMap<>();
        params.put("accountId", accountId);
        params.put("fromDate", fromDate);
        params.put("toDate", toDate);

        final String bucketExpression = "case when je.entry_date < :fromDate then null else je.entry_date end";
        final StringBuilder sql = new StringBuilder(" select ").append(bucketExpression).append(" as bucketDate, ")
                .append(sumOf(ENTRY_TYPE_DEBIT)).append(" as totalDebits, ").append(sumOf(ENTRY_TYPE_CREDIT)).append(" as totalCredits, ")
                .append(" count(*) as entryCount ").append(" from acc_gl_journal_entry je ").append(" where je.account_id = :accountId ")
                .append(" and je.entry_date <= :toDate");
        appendScopeFilters(sql, params, officeId, currencyCode);
        sql.append(" group by ").append(bucketExpression);

        return this.jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new MovementRow(JdbcSupport.getLocalDate(rs, "bucketDate"),
                rs.getBigDecimal("totalDebits"), rs.getBigDecimal("totalCredits"), rs.getLong("entryCount")));
    }

    /** {@code bucketDate == null} marks the pre-window group. */
    private record MovementRow(LocalDate bucketDate, BigDecimal totalDebits, BigDecimal totalCredits, Long entryCount) {
    }

    private static BigDecimal openingBalance(final GLAccountType type, final List<MovementRow> rows) {
        for (final MovementRow row : rows) {
            if (row.bucketDate() == null) {
                return GLAccountBalanceCalculator.signedNet(type, row.totalDebits(), row.totalCredits());
            }
        }
        return GLAccountBalanceCalculator.money(BigDecimal.ZERO);
    }

    private static List<GLAccountBalanceBucketData> buckets(final GLAccountBalanceGranularity granularity, final LocalDate fromDate,
            final LocalDate toDate, final List<MovementRow> rows) {
        return granularity == GLAccountBalanceGranularity.DAILY ? dailyBuckets(rows) : List.of(periodBucket(fromDate, toDate, rows));
    }

    /**
     * Ascending by date. The query has no {@code order by} — {@code nulls first} is not portable to MySQL and the
     * pre-window group has to be filtered out here anyway — so the ordering is applied in Java.
     */
    private static List<GLAccountBalanceBucketData> dailyBuckets(final List<MovementRow> rows) {
        final List<GLAccountBalanceBucketData> buckets = new ArrayList<>();
        for (final MovementRow row : rows) {
            if (row.bucketDate() == null) {
                continue;
            }
            buckets.add(
                    new GLAccountBalanceBucketData(row.bucketDate(), row.bucketDate(), GLAccountBalanceCalculator.money(row.totalDebits()),
                            GLAccountBalanceCalculator.money(row.totalCredits()), row.entryCount()));
        }
        buckets.sort(Comparator.comparing(GLAccountBalanceBucketData::fromDate));
        return buckets;
    }

    /** Always exactly one bucket, present even when the window had no movement. */
    private static GLAccountBalanceBucketData periodBucket(final LocalDate fromDate, final LocalDate toDate, final List<MovementRow> rows) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        long entryCount = 0L;
        for (final MovementRow row : rows) {
            if (row.bucketDate() == null) {
                continue;
            }
            debits = debits.add(GLAccountBalanceCalculator.money(row.totalDebits()));
            credits = credits.add(GLAccountBalanceCalculator.money(row.totalCredits()));
            entryCount += row.entryCount();
        }
        return new GLAccountBalanceBucketData(fromDate, toDate, GLAccountBalanceCalculator.money(debits),
                GLAccountBalanceCalculator.money(credits), entryCount);
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    /** Sum of one posting side over the whole filtered set. */
    private static String sumOf(final int entryType) {
        return " coalesce(sum(case when je.type_enum = " + entryType + " then je.amount else 0 end), 0) ";
    }

    /**
     * Same, restricted to entries on or before {@code :asOnDate}, so the cut-off does not cap {@code max(entry_date)}.
     */
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

    private static void validateWindow(final LocalDate fromDate, final LocalDate toDate, final GLAccountBalanceGranularity granularity) {
        if (DateUtils.isAfter(fromDate, toDate)) {
            throw validationError("validation.msg.glaccount.balance.fromDate.after.toDate",
                    "The parameter `fromDate` must not be after `toDate`.", "fromDate", fromDate, toDate);
        }
        if (granularity != GLAccountBalanceGranularity.DAILY) {
            return;
        }
        final long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (days > MAX_DAILY_BUCKETS) {
            throw validationError("validation.msg.glaccount.balance.daily.range.too.large",
                    "A DAILY balance window may span at most " + MAX_DAILY_BUCKETS + " days; " + days + " were requested.", "fromDate",
                    days, MAX_DAILY_BUCKETS);
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
