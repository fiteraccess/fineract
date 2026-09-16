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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class CreditRestrictionPageGuardTest {

    private JdbcTemplate jdbcTemplate;
    private CreditRestrictionPageGuard guard;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        guard = new CreditRestrictionPageGuard(jdbcTemplate);
    }

    @Test
    void locksEveryRowInThePageAndDropsTheOnesRestrictedSinceSelection() {
        var page = List.of(account(10L), account(20L), account(30L));
        stubFlags(Map.of(10L, false, 20L, true, 30L, false));

        var kept = guard.dropRestricted(page);

        assertThat(kept).extracting(SavingsAccountData::getId).containsExactly(10L, 30L);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class), eq(10L), eq(20L), eq(30L));
        assertThat(sql.getValue()).contains("FOR UPDATE").contains("IN (?,?,?)").doesNotContain("synapse_credit_restricted = true");
    }

    @Test
    void anUnrestrictedPageIsReturnedUntouched() {
        var page = List.of(account(10L), account(20L));
        stubFlags(Map.of(10L, false, 20L, false));

        assertThat(guard.dropRestricted(page)).isSameAs(page);
    }

    @Test
    void anEmptyPageNeverTouchesTheDatabase() {
        assertThat(guard.dropRestricted(List.of())).isEmpty();

        verifyNoInteractions(jdbcTemplate);
    }

    private void stubFlags(Map<Long, Boolean> flagsById) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (var entry : flagsById.entrySet()) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("id")).thenReturn(entry.getKey());
                when(rs.getBoolean("synapse_credit_restricted")).thenReturn(entry.getValue());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    private static SavingsAccountData account(long id) {
        SavingsAccountData account = mock(SavingsAccountData.class);
        when(account.getId()).thenReturn(id);
        return account;
    }
}
