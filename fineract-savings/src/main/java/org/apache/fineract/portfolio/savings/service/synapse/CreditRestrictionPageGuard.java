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
package org.apache.fineract.portfolio.savings.service.synapse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Closes the window between selecting a page of accounts for interest posting and writing their postings. The selection
 * excluded credit-restricted accounts, but a restriction applied after that — while the page waited in the queue —
 * would otherwise be posted through.
 *
 * <p>
 * Called inside the page's transaction, it locks <em>every</em> account row in the page ({@code FOR UPDATE}) and
 * re-reads the flag. From then until the page commits, the restriction replay's own write on any of those rows has to
 * wait, so a restriction is either seen here or lands strictly after the posting. Locking only the rows already
 * restricted would not do: the race is precisely on the rows that are not yet.
 */
@Slf4j
@RequiredArgsConstructor
public class CreditRestrictionPageGuard {

    private final JdbcTemplate jdbcTemplate;

    /** Returns the accounts that may still be posted; the rest are logged and dropped. */
    public List<SavingsAccountData> dropRestricted(final List<SavingsAccountData> page) {
        if (page.isEmpty()) {
            return page;
        }
        final Set<Long> restricted = lockAndFindRestricted(page);
        if (restricted.isEmpty()) {
            return page;
        }
        final List<SavingsAccountData> kept = new ArrayList<>(page.size() - restricted.size());
        for (SavingsAccountData account : page) {
            if (!restricted.contains(account.getId())) {
                kept.add(account);
            }
        }
        log.info("Interest posting skipped {} account(s) credit-restricted since selection: {}", restricted.size(), restricted);
        return kept;
    }

    private Set<Long> lockAndFindRestricted(final List<SavingsAccountData> page) {
        final List<Long> ids = new ArrayList<>(page.size());
        for (SavingsAccountData account : page) {
            ids.add(account.getId());
        }
        final String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        final String sql = "SELECT id, synapse_credit_restricted FROM m_savings_account WHERE id IN (" + placeholders + ") FOR UPDATE";
        final Set<Long> restricted = new HashSet<>();
        jdbcTemplate.query(sql, rs -> {
            if (rs.getBoolean("synapse_credit_restricted")) {
                restricted.add(rs.getLong("id"));
            }
        }, ids.toArray());
        return restricted;
    }
}
