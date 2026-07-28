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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceBucketData;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceData;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceGranularity;
import org.apache.fineract.accounting.glaccount.data.GLAccountDetailsData;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class GLAccountBalanceReadPlatformServiceImplTest {

    private static final String GL_CODE = "10101";
    private static final long ACCOUNT_ID = 41L;
    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 31);

    /** Matches the {@code from acc_gl_account gl} lookup, as opposed to the journal-entry aggregates. */
    private static final String ACCOUNT_SQL_MARKER = "from acc_gl_account gl";
    private static final String CUMULATIVE_SQL_MARKER = "lastMovementDate";
    private static final String MOVEMENTS_SQL_MARKER = "bucketDate";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private GLAccountBalanceReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        service = new GLAccountBalanceReadPlatformServiceImpl(jdbcTemplate);
        stubAccountLookup("ASSET");
    }

    // ------------------------------------------------------------------------------------------------------ fixtures

    /** Drives the real row mapper against a mocked {@link ResultSet}, so the mapping itself is under test too. */
    private void stubAccountLookup(final String type) {
        when(jdbcTemplate.queryForObject(contains(ACCOUNT_SQL_MARKER), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, RowMapper.class).mapRow(accountResultSet(type), 1));
    }

    private static ResultSet accountResultSet(final String type) throws SQLException {
        final int classification = switch (type) {
            case "ASSET" -> 1;
            case "LIABILITY" -> 2;
            case "EQUITY" -> 3;
            case "INCOME" -> 4;
            default -> 5;
        };
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(ACCOUNT_ID);
        when(rs.getString("glCode")).thenReturn(GL_CODE);
        when(rs.getString("name")).thenReturn("Cash at Main Vault");
        when(rs.getInt("classification")).thenReturn(classification);
        when(rs.getInt("accountUsage")).thenReturn(1);
        when(rs.getBoolean("disabled")).thenReturn(false);
        when(rs.getBoolean("manualEntriesAllowed")).thenReturn(true);
        when(rs.getString("description")).thenReturn("Vault cash");
        when(rs.getObject("parentId", Long.class)).thenReturn(40L);
        when(rs.getString("parentGlCode")).thenReturn("10100");
        when(rs.getString("parentName")).thenReturn("Cash");
        return rs;
    }

    private void stubCumulative(final String debits, final String credits, final long entryCount, final LocalDate lastMovement) {
        when(jdbcTemplate.queryForObject(contains(CUMULATIVE_SQL_MARKER), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            final ResultSet rs = mock(ResultSet.class);
            when(rs.getBigDecimal("totalDebits")).thenReturn(new BigDecimal(debits));
            when(rs.getBigDecimal("totalCredits")).thenReturn(new BigDecimal(credits));
            when(rs.getLong("entryCount")).thenReturn(entryCount);
            when(rs.getDate("lastMovementDate")).thenReturn(lastMovement == null ? null : Date.valueOf(lastMovement));
            return invocation.getArgument(2, RowMapper.class).mapRow(rs, 1);
        });
        when(jdbcTemplate.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("NGN"));
    }

    /** {@code bucketDate == null} is the pre-window group the service reads the opening balance from. */
    private record Row(LocalDate bucketDate, String debits, String credits, long entryCount) {
    }

    private void stubMovements(final Row... rows) {
        when(jdbcTemplate.query(contains(MOVEMENTS_SQL_MARKER), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            final RowMapper<?> mapper = invocation.getArgument(2, RowMapper.class);
            final List<Object> mapped = new ArrayList<>();
            for (final Row row : rows) {
                final ResultSet rs = mock(ResultSet.class);
                when(rs.getDate("bucketDate")).thenReturn(row.bucketDate() == null ? null : Date.valueOf(row.bucketDate()));
                when(rs.getBigDecimal("totalDebits")).thenReturn(new BigDecimal(row.debits()));
                when(rs.getBigDecimal("totalCredits")).thenReturn(new BigDecimal(row.credits()));
                when(rs.getLong("entryCount")).thenReturn(row.entryCount());
                mapped.add(mapper.mapRow(rs, 1));
            }
            return mapped;
        });
    }

    private GLAccountBalanceData balance(final GLAccountBalanceGranularity granularity, final LocalDate from, final LocalDate to) {
        return service.retrieveGLAccountBalanceByCode(GL_CODE, from, to, granularity, null, null);
    }

    private String capturedMovementsSql() {
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyMap(), any(RowMapper.class));
        return sql.getValue();
    }

    /** {@code queryForObject} serves both the account lookup and the cumulative aggregate, so pick by marker. */
    private String capturedCumulativeSql() {
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForObject(sql.capture(), anyMap(), any(RowMapper.class));
        return sql.getAllValues().stream().filter(candidate -> candidate.contains(CUMULATIVE_SQL_MARKER)).findFirst().orElseThrow();
    }

    // -------------------------------------------------------------------------------------------------------- lookups

    @Nested
    @DisplayName("account lookup")
    class AccountLookup {

        @Test
        void throwsNotFoundForAnUnknownGlCode() {
            when(jdbcTemplate.queryForObject(contains(ACCOUNT_SQL_MARKER), anyMap(), any(RowMapper.class)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            assertThatThrownBy(() -> balance(GLAccountBalanceGranularity.PERIOD, FROM, TO)).isInstanceOf(GLAccountNotFoundException.class)
                    .hasMessageContaining(GL_CODE);
        }

        @Test
        void resolvesTheAccountByCodeOnly() {
            stubMovements();

            balance(GLAccountBalanceGranularity.PERIOD, FROM, TO);

            final ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            verify(jdbcTemplate).queryForObject(contains(ACCOUNT_SQL_MARKER), params.capture(), any(RowMapper.class));
            assertThat(params.getValue()).containsExactly(Map.entry("glCode", GL_CODE));
        }
    }

    // ----------------------------------------------------------------------------------------------------- validation

    @Nested
    @DisplayName("window validation")
    class WindowValidation {

        @Test
        void rejectsFromDateAfterToDate() {
            assertThatThrownBy(() -> balance(GLAccountBalanceGranularity.PERIOD, TO, FROM))
                    .isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void acceptsADailyWindowAtTheBucketCap() {
            stubMovements();

            final GLAccountBalanceData result = balance(GLAccountBalanceGranularity.DAILY, FROM,
                    FROM.plusDays(GLAccountBalanceReadPlatformServiceImpl.MAX_DAILY_BUCKETS - 1));

            assertThat(result.granularity()).isEqualTo(GLAccountBalanceGranularity.DAILY);
        }

        @Test
        void rejectsADailyWindowBeyondTheBucketCap() {
            final LocalDate tooFar = FROM.plusDays(GLAccountBalanceReadPlatformServiceImpl.MAX_DAILY_BUCKETS);

            assertThatThrownBy(() -> balance(GLAccountBalanceGranularity.DAILY, FROM, tooFar))
                    .isInstanceOf(PlatformApiDataValidationException.class);
        }

        /** The cap exists to bound the bucket array, which PERIOD does not have. */
        @Test
        void appliesNoCapToAPeriodWindow() {
            stubMovements();

            final GLAccountBalanceData result = balance(GLAccountBalanceGranularity.PERIOD, LocalDate.of(1970, 1, 1), TO);

            assertThat(result.buckets()).hasSize(1);
        }
    }

    // -------------------------------------------------------------------------------------------------------- buckets

    @Nested
    @DisplayName("buckets")
    class Buckets {

        @Test
        void readsTheOpeningBalanceFromThePreWindowGroup() {
            stubMovements(new Row(null, "1500.000000", "500.000000", 9L));

            // ASSET: opening = debits - credits
            assertThat(balance(GLAccountBalanceGranularity.PERIOD, FROM, TO).openingBalance())
                    .isEqualByComparingTo(new BigDecimal("1000.000000"));
        }

        @Test
        void reportsAZeroOpeningBalanceWhenNothingPrecedesTheWindow() {
            stubMovements(new Row(FROM, "10.000000", "0.000000", 1L));

            assertThat(balance(GLAccountBalanceGranularity.PERIOD, FROM, TO).openingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void signsTheOpeningBalanceByAccountType() {
            stubAccountLookup("LIABILITY");
            stubMovements(new Row(null, "1500.000000", "500.000000", 9L));

            assertThat(balance(GLAccountBalanceGranularity.PERIOD, FROM, TO).openingBalance())
                    .isEqualByComparingTo(new BigDecimal("-1000.000000"));
        }

        @Test
        void aggregatesEveryInWindowDayIntoASinglePeriodBucket() {
            stubMovements(new Row(null, "5.000000", "0.000000", 1L), new Row(FROM, "100.000000", "40.000000", 3L),
                    new Row(FROM.plusDays(2), "20.000000", "10.000000", 2L));

            final List<GLAccountBalanceBucketData> buckets = balance(GLAccountBalanceGranularity.PERIOD, FROM, TO).buckets();

            assertThat(buckets).hasSize(1);
            assertThat(buckets.get(0).fromDate()).isEqualTo(FROM);
            assertThat(buckets.get(0).toDate()).isEqualTo(TO);
            assertThat(buckets.get(0).totalDebits()).isEqualByComparingTo(new BigDecimal("120.000000"));
            assertThat(buckets.get(0).totalCredits()).isEqualByComparingTo(new BigDecimal("50.000000"));
            assertThat(buckets.get(0).entryCount()).isEqualTo(5L);
        }

        /** A PERIOD request always answers with one bucket, so a caller never has to special-case an empty window. */
        @Test
        void returnsAZeroPeriodBucketWhenTheWindowHadNoMovement() {
            stubMovements(new Row(null, "5.000000", "0.000000", 1L));

            final List<GLAccountBalanceBucketData> buckets = balance(GLAccountBalanceGranularity.PERIOD, FROM, TO).buckets();

            assertThat(buckets).hasSize(1);
            assertThat(buckets.get(0).totalDebits()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(buckets.get(0).totalCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(buckets.get(0).entryCount()).isZero();
        }

        @Test
        void emitsOneDailyBucketPerDayWithMovementAndExcludesThePreWindowGroup() {
            stubMovements(new Row(null, "5.000000", "0.000000", 1L), new Row(FROM, "100.000000", "0.000000", 2L),
                    new Row(FROM.plusDays(2), "0.000000", "30.000000", 1L));

            final List<GLAccountBalanceBucketData> buckets = balance(GLAccountBalanceGranularity.DAILY, FROM, TO).buckets();

            assertThat(buckets).extracting(GLAccountBalanceBucketData::fromDate).containsExactly(FROM, FROM.plusDays(2));
            assertThat(buckets).allSatisfy(bucket -> assertThat(bucket.toDate()).isEqualTo(bucket.fromDate()));
        }

        /** The query has no {@code order by}, so ascending order is the service's responsibility. */
        @Test
        void ordersDailyBucketsAscendingRegardlessOfRowOrder() {
            stubMovements(new Row(FROM.plusDays(5), "1.000000", "0.000000", 1L), new Row(FROM, "2.000000", "0.000000", 1L),
                    new Row(FROM.plusDays(2), "3.000000", "0.000000", 1L));

            assertThat(balance(GLAccountBalanceGranularity.DAILY, FROM, TO).buckets()).extracting(GLAccountBalanceBucketData::fromDate)
                    .containsExactly(FROM, FROM.plusDays(2), FROM.plusDays(5));
        }

        @Test
        void returnsNoDailyBucketsWhenTheWindowHadNoMovement() {
            stubMovements(new Row(null, "5.000000", "0.000000", 1L));

            assertThat(balance(GLAccountBalanceGranularity.DAILY, FROM, TO).buckets()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------------------------ sql

    @Nested
    @DisplayName("sql shape")
    class SqlShape {

        @Test
        void neverReadsTheBatchMaintainedRunningBalanceColumns() {
            stubMovements();

            balance(GLAccountBalanceGranularity.PERIOD, FROM, TO);

            assertThat(capturedMovementsSql()).doesNotContain("organization_running_balance").doesNotContain("office_running_balance")
                    .doesNotContain("is_running_balance_calculated");
        }

        @Test
        void groupsPreWindowEntriesIntoTheOpeningBucket() {
            stubMovements();

            balance(GLAccountBalanceGranularity.PERIOD, FROM, TO);

            final String sql = capturedMovementsSql();
            assertThat(sql).contains("case when je.entry_date < :fromDate then null else je.entry_date end")
                    .contains("group by case when je.entry_date < :fromDate then null else je.entry_date end");
        }

        @Test
        void omitsScopePredicatesWhenNoFiltersAreSupplied() {
            stubMovements();

            balance(GLAccountBalanceGranularity.PERIOD, FROM, TO);

            final String sql = capturedMovementsSql();
            assertThat(sql).doesNotContain("office_id").doesNotContain("currency_code").doesNotContain("is null");
        }

        @Test
        void appendsScopePredicatesAndBindsThemWhenFiltersAreSupplied() {
            stubMovements();

            service.retrieveGLAccountBalanceByCode(GL_CODE, FROM, TO, GLAccountBalanceGranularity.PERIOD, 7L, "NGN");

            final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            verify(jdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));
            assertThat(sql.getValue()).contains("and je.office_id = :officeId").contains("and je.currency_code = :currencyCode");
            assertThat(params.getValue()).containsEntry("officeId", 7L).containsEntry("currencyCode", "NGN");
        }
    }

    // ---------------------------------------------------------------------------------------------------- gl details

    @Nested
    @DisplayName("account details")
    class AccountDetails {

        @Test
        void reportsTheTypeAwareBalanceAndRawTotals() {
            stubCumulative("20450000.550000", "10000000.000000", 482L, LocalDate.of(2026, 7, 27));

            final GLAccountDetailsData details = service.retrieveGLAccountDetailsByCode(GL_CODE, TO, null, null);

            assertThat(details.balance()).isEqualByComparingTo(new BigDecimal("10450000.550000"));
            assertThat(details.totalDebits()).isEqualByComparingTo(new BigDecimal("20450000.550000"));
            assertThat(details.totalCredits()).isEqualByComparingTo(new BigDecimal("10000000.000000"));
            assertThat(details.entryCount()).isEqualTo(482L);
            assertThat(details.asOnDate()).isEqualTo(TO);
        }

        @Test
        void reportsTheParentAndCurrenciesItResolved() {
            stubCumulative("1.000000", "0.000000", 1L, TO);

            final GLAccountDetailsData details = service.retrieveGLAccountDetailsByCode(GL_CODE, TO, null, null);

            assertThat(details.parentId()).isEqualTo(40L);
            assertThat(details.parentGlCode()).isEqualTo("10100");
            assertThat(details.currencies()).containsExactly("NGN");
            assertThat(details.type().getValue()).isEqualTo("ASSET");
        }

        @Test
        void reportsANullLastMovementDateForAnAccountNeverPostedTo() {
            stubCumulative("0.000000", "0.000000", 0L, null);

            final GLAccountDetailsData details = service.retrieveGLAccountDetailsByCode(GL_CODE, TO, null, null);

            assertThat(details.lastMovementDate()).isNull();
            assertThat(details.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * The cut-off sits inside each aggregate's {@code case}, not in the {@code where} clause, so a forward-dated
         * posting is still reported as the latest movement while staying out of the balance.
         */
        @Test
        void capsTheBalanceAtTheAsOnDateButNotTheLastMovementDate() {
            stubCumulative("1.000000", "0.000000", 1L, TO.plusMonths(1));

            service.retrieveGLAccountDetailsByCode(GL_CODE, TO, null, null);

            assertThat(capturedCumulativeSql()).contains("max(je.entry_date)").contains("case when je.entry_date <= :asOnDate");
        }

        @Test
        void echoesTheScopeFiltersItApplied() {
            stubCumulative("1.000000", "0.000000", 1L, TO);

            final GLAccountDetailsData details = service.retrieveGLAccountDetailsByCode(GL_CODE, TO, 7L, " NGN ");

            assertThat(details.officeId()).isEqualTo(7L);
            assertThat(details.currencyCode()).isEqualTo("NGN");
        }
    }
}
