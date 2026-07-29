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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceData;
import org.apache.fineract.accounting.glaccount.data.GLAccountDetailsData;
import org.apache.fineract.accounting.glaccount.exception.GLAccountMultipleCurrenciesException;
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
    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 31);

    /** Matches the {@code from acc_gl_account gl} lookup, as opposed to the journal-entry aggregates. */
    private static final String ACCOUNT_SQL_MARKER = "from acc_gl_account gl";
    private static final String CUMULATIVE_SQL_MARKER = "lastMovementDate";
    private static final String WINDOW_SQL_MARKER = "openingDebits";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private GLAccountBalanceReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        service = new GLAccountBalanceReadPlatformServiceImpl(jdbcTemplate);
        stubAccountLookup("ASSET");
        stubDistinctCurrencies("NGN");
    }

    // ------------------------------------------------------------------------------------------------------ fixtures

    /** Drives the real row mapper against a mocked {@link ResultSet}, so the mapping itself is under test too. */
    private void stubAccountLookup(final String type) {
        when(jdbcTemplate.queryForObject(contains(ACCOUNT_SQL_MARKER), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, RowMapper.class).mapRow(accountResultSet(type), 1));
    }

    private static java.sql.ResultSet accountResultSet(final String type) throws java.sql.SQLException {
        final int classification = switch (type) {
            case "ASSET" -> 1;
            case "LIABILITY" -> 2;
            case "EQUITY" -> 3;
            case "INCOME" -> 4;
            default -> 5;
        };
        final java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getLong("id")).thenReturn(41L);
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
            final java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getBigDecimal("totalDebits")).thenReturn(new BigDecimal(debits));
            when(rs.getBigDecimal("totalCredits")).thenReturn(new BigDecimal(credits));
            when(rs.getLong("entryCount")).thenReturn(entryCount);
            when(rs.getDate("lastMovementDate")).thenReturn(lastMovement == null ? null : java.sql.Date.valueOf(lastMovement));
            return invocation.getArgument(2, RowMapper.class).mapRow(rs, 1);
        });
    }

    private void stubWindow(final String openingDebits, final String openingCredits, final String debits, final String credits,
            final long entryCount) {
        when(jdbcTemplate.queryForObject(contains(WINDOW_SQL_MARKER), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            final java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getBigDecimal("openingDebits")).thenReturn(new BigDecimal(openingDebits));
            when(rs.getBigDecimal("openingCredits")).thenReturn(new BigDecimal(openingCredits));
            when(rs.getBigDecimal("totalDebits")).thenReturn(new BigDecimal(debits));
            when(rs.getBigDecimal("totalCredits")).thenReturn(new BigDecimal(credits));
            when(rs.getLong("entryCount")).thenReturn(entryCount);
            return invocation.getArgument(2, RowMapper.class).mapRow(rs, 1);
        });
    }

    private void stubDistinctCurrencies(final String... currencies) {
        when(jdbcTemplate.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of(currencies));
    }

    private GLAccountDetailsData details(final LocalDate asOnDate, final Long officeId, final String currencyCode) {
        return service.retrieveGLAccountDetailsByCode(GL_CODE, asOnDate, officeId, currencyCode);
    }

    private GLAccountBalanceData balance(final LocalDate from, final LocalDate to, final Long officeId, final String currencyCode) {
        return service.retrieveGLAccountBalanceByCode(GL_CODE, from, to, officeId, currencyCode);
    }

    private String capturedWindowSql() {
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).queryForObject(sql.capture(), anyMap(), any(RowMapper.class));
        return sql.getAllValues().stream().filter(s -> s.contains(WINDOW_SQL_MARKER)).findFirst().orElseThrow();
    }

    // -------------------------------------------------------------------------------------------------------- lookups

    @Nested
    @DisplayName("account lookup")
    class AccountLookup {

        @Test
        void throwsNotFoundForAnUnknownGlCode() {
            when(jdbcTemplate.queryForObject(contains(ACCOUNT_SQL_MARKER), anyMap(), any(RowMapper.class)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            assertThatThrownBy(() -> balance(FROM, TO, null, null)).isInstanceOf(GLAccountNotFoundException.class)
                    .hasMessageContaining(GL_CODE);
        }
    }

    // ----------------------------------------------------------------------------------------------------- validation

    @Nested
    @DisplayName("window validation")
    class WindowValidation {

        @Test
        void requiresFromDate() {
            assertThatThrownBy(() -> balance(null, TO, null, null)).isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void requiresToDate() {
            assertThatThrownBy(() -> balance(FROM, null, null, null)).isInstanceOf(PlatformApiDataValidationException.class);
        }

        @Test
        void rejectsFromDateAfterToDate() {
            assertThatThrownBy(() -> balance(TO, FROM, null, null)).isInstanceOf(PlatformApiDataValidationException.class);
        }

        /** No day-count cap applies any more: the response is one row regardless of how wide the window is. */
        @Test
        void acceptsAWindowSpanningManyYears() {
            stubWindow("0", "0", "0", "0", 0);

            assertThat(balance(LocalDate.of(1970, 1, 1), TO, null, null)).isNotNull();
        }
    }

    // -------------------------------------------------------------------------------------------------------- balance

    @Nested
    @DisplayName("balance")
    class Balance {

        @Test
        void computesOpeningFromEntriesStrictlyBeforeFromDate() {
            stubWindow("1500.000000", "500.000000", "0", "0", 0);

            // ASSET: opening = openingDebits - openingCredits
            assertThat(balance(FROM, TO, null, null).openingBalance()).isEqualByComparingTo(new BigDecimal("1000.000000"));
        }

        @Test
        void computesClosingAsOpeningPlusNetMovement() {
            stubWindow("1000.000000", "0", "100.000000", "40.000000", 3L);

            final GLAccountBalanceData result = balance(FROM, TO, null, null);

            assertThat(result.totalDebits()).isEqualByComparingTo(new BigDecimal("100.000000"));
            assertThat(result.totalCredits()).isEqualByComparingTo(new BigDecimal("40.000000"));
            assertThat(result.netMovement()).isEqualByComparingTo(new BigDecimal("60.000000"));
            assertThat(result.closingBalance()).isEqualByComparingTo(new BigDecimal("1060.000000"));
        }

        @Test
        void signsOpeningAndNetByAccountType() {
            stubAccountLookup("LIABILITY");
            stubWindow("1500.000000", "500.000000", "100.000000", "40.000000", 3L);

            final GLAccountBalanceData result = balance(FROM, TO, null, null);

            // LIABILITY: opening = openingCredits - openingDebits; net = credits - debits
            assertThat(result.openingBalance()).isEqualByComparingTo(new BigDecimal("-1000.000000"));
            assertThat(result.netMovement()).isEqualByComparingTo(new BigDecimal("-60.000000"));
        }

        @Test
        void echoesTheSuppliedWindowAndOfficeFilter() {
            stubWindow("0", "0", "0", "0", 0);

            final GLAccountBalanceData result = balance(FROM, TO, 7L, null);

            assertThat(result.fromDate()).isEqualTo(FROM);
            assertThat(result.toDate()).isEqualTo(TO);
            assertThat(result.officeId()).isEqualTo(7L);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ sql

    @Nested
    @DisplayName("sql shape")
    class SqlShape {

        @Test
        void neverReadsTheBatchMaintainedRunningBalanceColumns() {
            stubWindow("0", "0", "0", "0", 0);

            balance(FROM, TO, null, null);

            assertThat(capturedWindowSql()).doesNotContain("organization_running_balance").doesNotContain("office_running_balance")
                    .doesNotContain("is_running_balance_calculated");
        }

        @Test
        void excludesReversedEntriesAndTheirCounterpartFromTheWindowQuery() {
            stubWindow("0", "0", "0", "0", 0);

            balance(FROM, TO, null, null);

            final String sql = capturedWindowSql();
            assertThat(sql).contains("je.reversed = false").contains("not exists").contains("rev.reversal_id = je.id");
        }

        @Test
        void excludesReversedEntriesFromTheCumulativeQuery() {
            stubCumulative("0", "0", 0L, null);

            details(TO, null, "NGN");

            final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).queryForObject(sql.capture(), anyMap(), any(RowMapper.class));
            final String cumulativeSql = sql.getAllValues().stream().filter(s -> s.contains(CUMULATIVE_SQL_MARKER)).findFirst()
                    .orElseThrow();
            assertThat(cumulativeSql).contains("je.reversed = false").contains("not exists").contains("rev.reversal_id = je.id");
        }

        @Test
        void omitsScopePredicatesWhenNoFiltersAreSupplied() {
            stubWindow("0", "0", "0", "0", 0);

            balance(FROM, TO, null, null);

            final String sql = capturedWindowSql();
            assertThat(sql).doesNotContain("office_id").doesNotContain("currency_code").doesNotContain("is null");
        }

        @Test
        void appendsScopePredicatesAndBindsThemWhenFiltersAreSupplied() {
            stubWindow("0", "0", "0", "0", 0);

            service.retrieveGLAccountBalanceByCode(GL_CODE, FROM, TO, 7L, "NGN");

            final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).queryForObject(sql.capture(), params.capture(), any(RowMapper.class));

            final int windowIndex = sql.getAllValues().stream().filter(s -> s.contains(WINDOW_SQL_MARKER)).findFirst()
                    .map(s -> sql.getAllValues().indexOf(s)).orElseThrow();
            assertThat(sql.getAllValues().get(windowIndex)).contains("and je.office_id = :officeId")
                    .contains("and je.currency_code = :currencyCode");
            assertThat(params.getAllValues().get(windowIndex)).containsEntry("officeId", 7L).containsEntry("currencyCode", "NGN");
        }
    }

    // ---------------------------------------------------------------------------------------------------- currency

    @Nested
    @DisplayName("currency resolution")
    class CurrencyResolution {

        @Test
        void echoesTheSuppliedCurrencyFilterWithoutQueryingDistinctCurrencies() {
            stubWindow("0", "0", "0", "0", 0);

            final GLAccountBalanceData result = balance(FROM, TO, null, "ngn");

            assertThat(result.currency()).isEqualTo("ngn");
            verify(jdbcTemplate, org.mockito.Mockito.never()).queryForList(anyString(), anyMap(), eq(String.class));
        }

        @Test
        void resolvesTheSoleCurrencyWhenNoFilterIsSupplied() {
            stubWindow("0", "0", "0", "0", 0);
            stubDistinctCurrencies("NGN");

            assertThat(balance(FROM, TO, null, null).currency()).isEqualTo("NGN");
        }

        @Test
        void resolvesNullCurrencyWhenNeverPostedTo() {
            stubWindow("0", "0", "0", "0", 0);
            stubDistinctCurrencies();

            assertThat(balance(FROM, TO, null, null).currency()).isNull();
        }

        @Test
        void throwsWhenMoreThanOneCurrencyWasPostedAndNoFilterWasSupplied() {
            stubWindow("0", "0", "0", "0", 0);
            stubDistinctCurrencies("NGN", "USD");

            assertThatThrownBy(() -> balance(FROM, TO, null, null)).isInstanceOf(GLAccountMultipleCurrenciesException.class)
                    .hasMessageContaining(GL_CODE);
        }

        @Test
        void appliesTheSameResolutionToAccountDetails() {
            stubCumulative("0", "0", 0L, null);
            stubDistinctCurrencies("NGN", "USD");

            assertThatThrownBy(() -> details(TO, null, null)).isInstanceOf(GLAccountMultipleCurrenciesException.class);
        }
    }

    // ---------------------------------------------------------------------------------------------------- gl details

    @Nested
    @DisplayName("account details")
    class AccountDetails {

        @Test
        void reportsTheTypeAwareBalanceAndRawTotals() {
            stubCumulative("20450000.550000", "10000000.000000", 482L, LocalDate.of(2026, 7, 27));

            final GLAccountDetailsData result = details(TO, null, "NGN");

            assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("10450000.550000"));
            assertThat(result.totalDebits()).isEqualByComparingTo(new BigDecimal("20450000.550000"));
            assertThat(result.totalCredits()).isEqualByComparingTo(new BigDecimal("10000000.000000"));
            assertThat(result.entryCount()).isEqualTo(482L);
            assertThat(result.asOnDate()).isEqualTo(TO);
            assertThat(result.currency()).isEqualTo("NGN");
        }

        @Test
        void reportsTheParent() {
            stubCumulative("1.000000", "0.000000", 1L, TO);

            final GLAccountDetailsData result = details(TO, null, "NGN");

            assertThat(result.parentId()).isEqualTo(40L);
            assertThat(result.parentGlCode()).isEqualTo("10100");
            assertThat(result.type().getValue()).isEqualTo("ASSET");
        }

        @Test
        void reportsANullLastMovementDateForAnAccountNeverPostedTo() {
            stubCumulative("0.000000", "0.000000", 0L, null);
            stubDistinctCurrencies();

            final GLAccountDetailsData result = details(TO, null, null);

            assertThat(result.lastMovementDate()).isNull();
            assertThat(result.currency()).isNull();
            assertThat(result.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * The cut-off sits inside each aggregate's {@code case}, not in the {@code where} clause, so a forward-dated
         * posting is still reported as the latest movement while staying out of the balance.
         */
        @Test
        void capsTheBalanceAtTheAsOnDateButNotTheLastMovementDate() {
            stubCumulative("1.000000", "0.000000", 1L, TO.plusMonths(1));

            details(TO, null, "NGN");

            final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).queryForObject(sql.capture(), anyMap(), any(RowMapper.class));
            final String cumulativeSql = sql.getAllValues().stream().filter(s -> s.contains(CUMULATIVE_SQL_MARKER)).findFirst()
                    .orElseThrow();
            assertThat(cumulativeSql).contains("max(je.entry_date)").contains("case when je.entry_date <= :asOnDate");
        }

        @Test
        void echoesTheOfficeFilterItApplied() {
            stubCumulative("1.000000", "0.000000", 1L, TO);

            assertThat(details(TO, 7L, "NGN").officeId()).isEqualTo(7L);
        }
    }
}
