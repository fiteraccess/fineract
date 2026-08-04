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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.accounting.journalentry.data.JournalEntryAssociationParametersData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class GLAccountReadPlatformServiceImplTest {

    private JdbcTemplate jdbcTemplate;
    private GLAccountReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new GLAccountReadPlatformServiceImpl(jdbcTemplate);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    /**
     * Regression guard: the predicate used to read {@code name like %?% or gl_code like %?%}, which is a syntax error
     * on both PostgreSQL and MySQL, so any caller passing {@code searchParam} got a 500. The wildcards belong in the
     * bound value.
     */
    @Test
    void bindsSearchParamWildcardsAsValuesNotSql() {
        service.retrieveAllGLAccounts(null, "cash", null, null, null, new JournalEntryAssociationParametersData());

        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), params.capture());

        assertThat(sql.getValue()).contains("( name like ? or gl_code like ? )").doesNotContain("%?%");
        assertThat(params.getValue()).containsExactly("%cash%", "%cash%");
    }

    @Test
    void bindsNoParametersWhenNoFiltersAreSupplied() {
        service.retrieveAllGLAccounts(null, null, null, null, null, new JournalEntryAssociationParametersData());

        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), params.capture());

        assertThat(sql.getValue()).doesNotContain(" where");
        assertThat(params.getValue()).isEmpty();
    }

    @Test
    void combinesClassificationAndSearchParamFilters() {
        service.retrieveAllGLAccounts(1, "vault", null, null, null, new JournalEntryAssociationParametersData());

        final ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), params.capture());

        assertThat(params.getValue()).containsExactly((short) 1, "%vault%", "%vault%");
    }
}
