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
package org.apache.fineract.portfolio.savings.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * AB-566: Synapse's per-month interest eligibility of a Regular Savings account, in
 * {@code m_savings_interest_forfeiture}. A row exists only for months Synapse reported a transition in; no row means
 * the month is eligible.
 */
@Repository
@RequiredArgsConstructor
public class SavingsInterestForfeitureRepository {

    private final JdbcTemplate jdbcTemplate;

    public record Transition(Long savingsAccountId, LocalDate governingMonth, boolean forfeited, long transitionSeq,
            String sourceTransitionId) {
    }

    /** Every account's state for the governing months in the range, keyed by account then month. One query. */
    public Map<Long, Map<LocalDate, Boolean>> findStatesBetween(final LocalDate fromMonth, final LocalDate toMonth) {
        final Map<Long, Map<LocalDate, Boolean>> states = new HashMap<>();
        jdbcTemplate.query("SELECT savings_account_id, governing_month, forfeited FROM m_savings_interest_forfeiture "
                + "WHERE governing_month >= ? AND governing_month <= ?", rs -> {
                    states.computeIfAbsent(rs.getLong("savings_account_id"), id -> new TreeMap<>())
                            .put(JdbcSupport.getLocalDate(rs, "governing_month"), rs.getBoolean("forfeited"));
                }, fromMonth, toMonth);
        return states;
    }

    /** The forfeited governing months, from the given month on, of the given accounts. One query. */
    public Map<Long, Set<LocalDate>> findForfeitedMonths(final Collection<Long> savingsAccountIds, final LocalDate fromMonth) {
        if (savingsAccountIds.isEmpty()) {
            return Map.of();
        }
        final List<Object> args = new ArrayList<>(savingsAccountIds);
        args.add(fromMonth);
        args.add(Boolean.TRUE);
        final String placeholders = String.join(",", Collections.nCopies(savingsAccountIds.size(), "?"));
        final Map<Long, Set<LocalDate>> forfeited = new HashMap<>();
        jdbcTemplate.query("SELECT savings_account_id, governing_month FROM m_savings_interest_forfeiture WHERE savings_account_id IN ("
                + placeholders + ") AND governing_month >= ? AND forfeited = ?", rs -> {
                    forfeited.computeIfAbsent(rs.getLong("savings_account_id"), id -> new HashSet<>())
                            .add(JdbcSupport.getLocalDate(rs, "governing_month"));
                }, args.toArray());
        return forfeited;
    }

    /**
     * Records the transition unless the stored one for the same account and month has an equal or higher sequence.
     * Returns whether it was recorded.
     */
    public boolean applyIfNewer(final Transition transition) {
        final int updated = jdbcTemplate.update(
                "UPDATE m_savings_interest_forfeiture SET forfeited = ?, transition_seq = ?, source_transition_id = ?, updated_on_utc = ? "
                        + "WHERE savings_account_id = ? AND governing_month = ? AND transition_seq < ?",
                transition.forfeited(), transition.transitionSeq(), transition.sourceTransitionId(), DateUtils.getAuditLocalDateTime(),
                transition.savingsAccountId(), transition.governingMonth(), transition.transitionSeq());
        if (updated > 0) {
            return true;
        }
        final Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_savings_interest_forfeiture WHERE savings_account_id = ? AND governing_month = ?", Integer.class,
                transition.savingsAccountId(), transition.governingMonth());
        if (existing != null && existing > 0) {
            return false;
        }
        // A concurrent first transition for the same month fails on the primary key and is redelivered by Synapse.
        jdbcTemplate.update(
                "INSERT INTO m_savings_interest_forfeiture (savings_account_id, governing_month, forfeited, transition_seq, "
                        + "source_transition_id, updated_on_utc) VALUES (?, ?, ?, ?, ?, ?)",
                transition.savingsAccountId(), transition.governingMonth(), transition.forfeited(), transition.transitionSeq(),
                transition.sourceTransitionId(), DateUtils.getAuditLocalDateTime());
        return true;
    }
}
