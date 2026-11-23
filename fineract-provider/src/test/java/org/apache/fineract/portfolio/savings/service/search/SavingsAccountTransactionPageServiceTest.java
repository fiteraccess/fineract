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
package org.apache.fineract.portfolio.savings.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.portfolio.savings.service.search.SavingsAccountTransactionPageService.PageRequest;
import org.apache.fineract.portfolio.savings.service.search.SavingsAccountTransactionPageService.TransactionPage;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavingsAccountTransactionPageServiceTest {

    private static final long SAVINGS_ID = 476L;
    private static final String ACCOUNT_SQL = "SELECT deposit_type_enum FROM m_savings_account WHERE id = ?";
    private static final String KEY_PREFIX = "SELECT tr.id, tr.transaction_date FROM m_savings_account_transaction tr";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;

    private SavingsAccountTransactionPageService underTest;
    private final List<SavingsAccountTransactionData> hydratedRows = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
        when(sqlGenerator.limit(anyInt())).thenAnswer(inv -> "LIMIT " + inv.getArgument(0));
        when(jdbcTemplate.queryForList(eq(ACCOUNT_SQL), eq(Integer.class), any(Object[].class)))
                .thenReturn(List.of(DepositAccountType.SAVINGS_DEPOSIT.getValue()));
        whenHydrateReturn();
        underTest = new SavingsAccountTransactionPageService(context, jdbcTemplate, sqlGenerator);
    }

    @Test
    void readsLimitPlusOneKeysWithoutCountOrJoinsAndHydratesThePageInKeyOrder() throws Exception {
        whenKeysReturn(key(30L), key(20L), key(10L), key(5L));
        SavingsAccountTransactionData t10 = transaction(10L);
        SavingsAccountTransactionData t20 = transaction(20L);
        SavingsAccountTransactionData t20SecondNote = transaction(20L);
        SavingsAccountTransactionData t30 = transaction(30L);
        // Hydration returns rows in arbitrary order and repeats a transaction that has two notes.
        hydratedRows.addAll(List.of(t10, t20, t20SecondNote, t30));

        TransactionPage page = underTest.retrievePage(SAVINGS_ID, new PageRequest(null, 3, null, List.of(10), false, null, null));

        assertThat(page.pageItems()).containsExactly(t30, t20, t10);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(SavingsAccountTransactionPageService.encodeCursor(DAY, 10L));

        KeyQuery keyQuery = capturedKeyQuery();
        assertThat(keyQuery.sql()).doesNotContainIgnoringCase("join").doesNotContainIgnoringCase("count(")
                .isEqualTo(KEY_PREFIX + " WHERE tr.savings_account_id = ? AND tr.is_reversed = ? AND tr.transaction_type_enum NOT IN (?)"
                        + " ORDER BY tr.transaction_date DESC, tr.id DESC LIMIT 4");
        assertThat(keyQuery.args()).containsExactly(SAVINGS_ID, false, 10);
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
    }

    @Test
    void cursorSeeksPastTheLastKeyAndLastPageHasNoCursor() throws Exception {
        whenKeysReturn(key(4L));
        hydratedRows.add(transaction(4L));
        String cursor = SavingsAccountTransactionPageService.encodeCursor(DAY, 5L);

        TransactionPage page = underTest.retrievePage(SAVINGS_ID, new PageRequest(cursor, 3, null, null, null, null, null));

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        KeyQuery keyQuery = capturedKeyQuery();
        assertThat(keyQuery.sql()).isEqualTo(KEY_PREFIX + " WHERE tr.savings_account_id = ? AND (tr.transaction_date, tr.id) < (?, ?)"
                + " ORDER BY tr.transaction_date DESC, tr.id DESC LIMIT 4");
        assertThat(keyQuery.args()).containsExactly(SAVINGS_ID, DAY, 5L);
    }

    @Test
    void ascendingSeeksForwardOnTheSameKey() throws Exception {
        whenKeysReturn();
        String cursor = SavingsAccountTransactionPageService.encodeCursor(DAY, 5L);

        TransactionPage page = underTest.retrievePage(SAVINGS_ID, new PageRequest(cursor, 2, List.of(1, 2), null, null, true, "asc"));

        assertThat(page.pageItems()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        KeyQuery keyQuery = capturedKeyQuery();
        assertThat(keyQuery.sql())
                .isEqualTo(KEY_PREFIX + " WHERE tr.savings_account_id = ? AND tr.is_reversal = ? AND tr.transaction_type_enum IN (?,?)"
                        + " AND (tr.transaction_date, tr.id) > (?, ?) ORDER BY tr.transaction_date ASC, tr.id ASC LIMIT 3");
        assertThat(keyQuery.args()).containsExactly(SAVINGS_ID, true, 1, 2, DAY, 5L);
    }

    @Test
    void defaultsToFiftyNewestFirst() throws Exception {
        whenKeysReturn();

        underTest.retrievePage(SAVINGS_ID, new PageRequest(null, null, null, null, null, null, null));

        verify(jdbcTemplate).query(
                eq(KEY_PREFIX + " WHERE tr.savings_account_id = ? ORDER BY tr.transaction_date DESC, tr.id DESC LIMIT 51"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void rejectsUnknownOrNonSavingsAccounts() {
        when(jdbcTemplate.queryForList(eq(ACCOUNT_SQL), eq(Integer.class), any(Object[].class))).thenReturn(List.of());
        assertThatThrownBy(() -> underTest.retrievePage(SAVINGS_ID, new PageRequest(null, null, null, null, null, null, null)))
                .isInstanceOf(SavingsAccountNotFoundException.class);

        when(jdbcTemplate.queryForList(eq(ACCOUNT_SQL), eq(Integer.class), any(Object[].class)))
                .thenReturn(List.of(DepositAccountType.FIXED_DEPOSIT.getValue()));
        assertThatThrownBy(() -> underTest.retrievePage(SAVINGS_ID, new PageRequest(null, null, null, null, null, null, null)))
                .isInstanceOf(SavingsAccountNotFoundException.class);
    }

    @Test
    void rejectsInvalidParametersBeforeTouchingTheDatabase() {
        assertInvalid(new PageRequest(null, 0, null, null, null, null, null));
        assertInvalid(new PageRequest(null, SavingsAccountTransactionPageService.MAX_LIMIT + 1, null, null, null, null, null));
        assertInvalid(new PageRequest(null, null, null, null, null, null, "sideways"));
        assertInvalid(new PageRequest(null, null, List.of(9999), null, null, null, null));
        assertInvalid(new PageRequest(null, null, null, List.of(-5), null, null, null));
        assertInvalid(new PageRequest("not-a-cursor!", null, null, null, null, null, null));
        assertInvalid(new PageRequest(SavingsAccountTransactionPageService.encodeCursor(DAY, 1L).substring(3), null, null, null, null, null,
                null));
        verify(jdbcTemplate, never()).queryForList(eq(ACCOUNT_SQL), eq(Integer.class), any(Object[].class));
    }

    private void assertInvalid(PageRequest request) {
        assertThatThrownBy(() -> underTest.retrievePage(SAVINGS_ID, request)).isInstanceOf(PlatformApiDataValidationException.class);
    }

    private record KeyQuery(String sql, Object[] args) {
    }

    /** The key (page-id) query; the other {@code query} call is the hydration of the page. */
    @SuppressWarnings("unchecked")
    private KeyQuery capturedKeyQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).query(sql.capture(), any(RowMapper.class), args.capture());
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).startsWith(KEY_PREFIX)) {
                return new KeyQuery(sql.getAllValues().get(i), args.getAllValues().get(i));
            }
        }
        throw new AssertionError("no key query was run");
    }

    private record KeyRow(Long id, LocalDate date) {
    }

    private static KeyRow key(Long id) {
        return new KeyRow(id, DAY);
    }

    /** Runs the service's own key RowMapper over fake result-set rows, so the mapping is exercised too. */
    @SuppressWarnings("unchecked")
    private void whenKeysReturn(KeyRow... rows) throws Exception {
        when(jdbcTemplate.query(startsWith(KEY_PREFIX), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (int i = 0; i < rows.length; i++) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("id")).thenReturn(rows[i].id());
                when(rs.getObject("transaction_date", LocalDate.class)).thenReturn(rows[i].date());
                mapped.add(mapper.mapRow(rs, i));
            }
            return mapped;
        });
    }

    @SuppressWarnings("unchecked")
    private void whenHydrateReturn() {
        when(jdbcTemplate.query(startsWith("select "), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> hydratedRows);
    }

    private static SavingsAccountTransactionData transaction(Long id) {
        SavingsAccountTransactionData data = mock(SavingsAccountTransactionData.class);
        when(data.getId()).thenReturn(id);
        return data;
    }
}
