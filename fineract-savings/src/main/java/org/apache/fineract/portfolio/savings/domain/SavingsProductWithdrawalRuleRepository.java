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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores {@link SavingsProductWithdrawalRule}s in {@code m_savings_product_withdrawal_rule}. */
@Repository
@RequiredArgsConstructor
public class SavingsProductWithdrawalRuleRepository {

    private static final String COLUMNS = "product_id, effective_from, withdrawal_counter_enabled, monthly_withdrawal_threshold, "
            + "qualifying_withdrawal_types";

    private final JdbcTemplate jdbcTemplate;

    public List<SavingsProductWithdrawalRule> findByProductId(final Long productId) {
        final List<SavingsProductWithdrawalRule> rules = new ArrayList<>();
        jdbcTemplate.query("SELECT " + COLUMNS + " FROM m_savings_product_withdrawal_rule WHERE product_id = ? ORDER BY effective_from",
                rs -> {
                    rules.add(map(rs));
                }, productId);
        return rules;
    }

    /** Every product's rules in one query; the table holds a row per product per month a rule changed. */
    public Map<Long, List<SavingsProductWithdrawalRule>> findAllByProductId() {
        final Map<Long, List<SavingsProductWithdrawalRule>> rules = new HashMap<>();
        jdbcTemplate.query("SELECT " + COLUMNS + " FROM m_savings_product_withdrawal_rule ORDER BY product_id, effective_from", rs -> {
            rules.computeIfAbsent(rs.getLong("product_id"), id -> new ArrayList<>()).add(map(rs));
        });
        return rules;
    }

    /** Inserts the rule, or replaces the product's rule from the same month. */
    public void save(final Long productId, final SavingsProductWithdrawalRule rule) {
        final int updated = jdbcTemplate.update(
                "UPDATE m_savings_product_withdrawal_rule SET withdrawal_counter_enabled = ?, monthly_withdrawal_threshold = ?, "
                        + "qualifying_withdrawal_types = ? WHERE product_id = ? AND effective_from = ?",
                rule.withdrawalCounterEnabled(), rule.monthlyWithdrawalThreshold(), rule.typesCsv(), productId, rule.effectiveFrom());
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO m_savings_product_withdrawal_rule (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)", productId,
                    rule.effectiveFrom(), rule.withdrawalCounterEnabled(), rule.monthlyWithdrawalThreshold(), rule.typesCsv());
        }
    }

    private static SavingsProductWithdrawalRule map(final ResultSet rs) throws SQLException {
        return new SavingsProductWithdrawalRule(JdbcSupport.getLocalDate(rs, "effective_from"), rs.getBoolean("withdrawal_counter_enabled"),
                rs.getInt("monthly_withdrawal_threshold"),
                SavingsProductWithdrawalRule.parseTypesCsv(rs.getString("qualifying_withdrawal_types")));
    }
}
