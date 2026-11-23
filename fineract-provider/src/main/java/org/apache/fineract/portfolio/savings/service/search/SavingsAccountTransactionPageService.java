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

import static org.apache.fineract.portfolio.savings.SavingsApiConstants.SAVINGS_ACCOUNT_RESOURCE_NAME;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformServiceImpl.SavingsAccountTransactionsMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves one page of a savings account's transactions for {@code GET
 * /v1/savingsaccounts/{savingsId}/transactions/page}.
 *
 * <p>
 * The account view used to load every transaction through {@code ?associations=all}; on long histories that response
 * froze the UI and loaded the proxy. The community {@code /transactions/search} pages, but each page runs a COUNT over
 * the account's whole history with the full read-model join set and sorts without a matching index. This endpoint does
 * neither:
 * <ul>
 * <li><b>No COUNT.</b> Paging is keyset: the page query fetches {@code limit + 1} ids, the extra one only signalling
 * {@code hasMore}, and the caller echoes {@code nextCursor} to continue. Cost per page stays flat however deep the
 * caller pages or however long the history is.</li>
 * <li><b>Index-only id read.</b> Filters and order use only the columns of {@code idx_sat_account_page}
 * ({@code savings_account_id, transaction_date, id} + included filter columns), so the ids come straight off the index
 * in order and the scan stops at LIMIT.</li>
 * <li><b>Joins for the page only.</b> The read-model joins (transfers, notes, payment details, users) run for the
 * {@code limit} ids of the page.</li>
 * </ul>
 * Rows keep the exact shape of {@code associations=all} (same mapper). The order is fixed to transaction date then id
 * (ids are insertion-ordered, so within a day this is creation order); any other order would sort the whole history.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SavingsAccountTransactionPageService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    private static final String RESOURCE = "savingsaccount.transactions.page";
    private static final String CURSOR_SEPARATOR = "|";
    private static final SavingsAccountTransactionsMapper TRANSACTIONS_MAPPER = new SavingsAccountTransactionsMapper();

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;

    /**
     * Page query parameters. Nullable filters mean "no filter".
     *
     * @param cursor
     *            {@code nextCursor} of the previous page; null for the first page
     * @param limit
     *            page size, default {@value #DEFAULT_LIMIT}, at most {@value #MAX_LIMIT}
     * @param types
     *            only these transaction type ids
     * @param excludeTypes
     *            none of these transaction type ids (e.g. 10 = accrual)
     * @param reversed
     *            only reversed ({@code true}) or only live ({@code false}) transactions
     * @param reversal
     *            only reversal contra rows ({@code true}) or no contra rows ({@code false})
     * @param sortOrder
     *            {@code DESC} (default, newest first) or {@code ASC}
     */
    public record PageRequest(String cursor, Integer limit, List<Integer> types, List<Integer> excludeTypes, Boolean reversed,
            Boolean reversal, String sortOrder) {
    }

    /**
     * One page. {@code nextCursor} is null exactly when {@code hasMore} is false. A cursor is only meaningful with the
     * same filters and sort order it was issued under.
     */
    public record TransactionPage(List<SavingsAccountTransactionData> pageItems, boolean hasMore, String nextCursor) {
    }

    private record Key(LocalDate transactionDate, Long id) {
    }

    public TransactionPage retrievePage(final Long savingsId, final PageRequest request) {
        this.context.authenticatedUser().validateHasReadPermission(SAVINGS_ACCOUNT_RESOURCE_NAME);
        final Key after = validateAndDecodeCursor(request);
        assertSavingsAccountExists(savingsId);

        final int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        final boolean ascending = "ASC".equalsIgnoreCase(request.sortOrder());
        final String direction = ascending ? " ASC" : " DESC";

        final List<Object> params = new ArrayList<>();
        final StringBuilder sql = new StringBuilder("SELECT tr.id, tr.transaction_date FROM m_savings_account_transaction tr");
        appendWhere(sql, params, savingsId, request);
        if (after != null) {
            // Row-value comparison keeps the seek on the (transaction_date, id) index key.
            sql.append(" AND (tr.transaction_date, tr.id) ").append(ascending ? '>' : '<').append(" (?, ?)");
            params.add(after.transactionDate());
            params.add(after.id());
        }
        sql.append(" ORDER BY tr.transaction_date").append(direction).append(", tr.id").append(direction).append(' ')
                .append(this.sqlGenerator.limit(limit + 1));

        final List<Key> keys = this.jdbcTemplate.query(sql.toString(), // NOSONAR - fixed fragments, values are bound
                (rs, rowNum) -> new Key(rs.getObject("transaction_date", LocalDate.class), rs.getLong("id")), params.toArray());

        final boolean hasMore = keys.size() > limit;
        final List<Key> pageKeys = hasMore ? keys.subList(0, limit) : keys;
        if (pageKeys.isEmpty()) {
            return new TransactionPage(List.of(), false, null);
        }
        final String nextCursor = hasMore ? encodeCursor(pageKeys.get(pageKeys.size() - 1)) : null;
        return new TransactionPage(hydrate(pageKeys.stream().map(Key::id).toList()), hasMore, nextCursor);
    }

    /**
     * Loads the read-model rows for the page and returns them in page order. The mapper's note join is one-to-many, so
     * a transaction with several notes would come back once per note; only its first row is kept.
     */
    private List<SavingsAccountTransactionData> hydrate(final List<Long> pageIds) {
        final String placeholders = pageIds.stream().map(id -> "?").collect(Collectors.joining(","));
        final String sql = "select " + TRANSACTIONS_MAPPER.schema() + " where tr.id in (" + placeholders + ")";
        final List<SavingsAccountTransactionData> rows = this.jdbcTemplate.query(sql, TRANSACTIONS_MAPPER, pageIds.toArray()); // NOSONAR

        final Map<Long, SavingsAccountTransactionData> byId = new LinkedHashMap<>();
        for (SavingsAccountTransactionData row : rows) {
            byId.putIfAbsent(row.getId(), row);
        }
        final List<SavingsAccountTransactionData> ordered = new ArrayList<>(pageIds.size());
        for (Long id : pageIds) {
            final SavingsAccountTransactionData row = byId.get(id);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    private static void appendWhere(final StringBuilder sql, final List<Object> params, final Long savingsId, final PageRequest request) {
        sql.append(" WHERE tr.savings_account_id = ?");
        params.add(savingsId);
        if (request.reversed() != null) {
            sql.append(" AND tr.is_reversed = ?");
            params.add(request.reversed());
        }
        if (request.reversal() != null) {
            sql.append(" AND tr.is_reversal = ?");
            params.add(request.reversal());
        }
        appendTypeFilter(sql, params, request.types(), " IN ");
        appendTypeFilter(sql, params, request.excludeTypes(), " NOT IN ");
    }

    private static void appendTypeFilter(final StringBuilder sql, final List<Object> params, final List<Integer> types,
            final String operator) {
        if (types == null || types.isEmpty()) {
            return;
        }
        sql.append(" AND tr.transaction_type_enum").append(operator).append('(')
                .append(types.stream().map(t -> "?").collect(Collectors.joining(","))).append(')');
        params.addAll(types);
    }

    private void assertSavingsAccountExists(final Long savingsId) {
        final List<Integer> depositTypes = this.jdbcTemplate.queryForList("SELECT deposit_type_enum FROM m_savings_account WHERE id = ?",
                Integer.class, savingsId);
        if (depositTypes.isEmpty() || !DepositAccountType.SAVINGS_DEPOSIT.getValue().equals(depositTypes.get(0))) {
            throw new SavingsAccountNotFoundException(savingsId);
        }
    }

    static String encodeCursor(final LocalDate transactionDate, final Long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((transactionDate + CURSOR_SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeCursor(final Key key) {
        return encodeCursor(key.transactionDate(), key.id());
    }

    private static Key validateAndDecodeCursor(final PageRequest request) {
        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE);
        validator.reset().parameter("limit").value(request.limit()).ignoreIfNull().integerGreaterThanZero().notGreaterThanMax(MAX_LIMIT);
        // isOneOfTheseStringValues lower-cases the value before comparing, so the allowed values must be lower case.
        validator.reset().parameter("sortOrder").value(request.sortOrder()).ignoreIfNull().isOneOfTheseStringValues("asc", "desc");
        validateTypes(validator, "types", request.types());
        validateTypes(validator, "excludeTypes", request.excludeTypes());
        final Key cursor = decodeCursor(validator, request.cursor());
        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
        return cursor;
    }

    private static Key decodeCursor(final DataValidatorBuilder validator, final String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            final String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            final int separator = decoded.indexOf(CURSOR_SEPARATOR);
            if (separator > 0) {
                return new Key(LocalDate.parse(decoded.substring(0, separator)), Long.valueOf(decoded.substring(separator + 1)));
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // NumberFormatException is an IllegalArgumentException; fall through to the validation error.
        }
        validator.reset().parameter("cursor").value(cursor).failWithCode("invalid");
        return null;
    }

    private static void validateTypes(final DataValidatorBuilder validator, final String parameter, final List<Integer> types) {
        if (types == null) {
            return;
        }
        for (Integer type : types) {
            if (type == null || !SavingsAccountTransactionType.fromInt(type).isValid()) {
                validator.reset().parameter(parameter).value(type).failWithCode("invalid.transaction.type");
            }
        }
    }
}
