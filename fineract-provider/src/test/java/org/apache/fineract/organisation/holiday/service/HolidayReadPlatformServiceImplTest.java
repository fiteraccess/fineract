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
package org.apache.fineract.organisation.holiday.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class HolidayReadPlatformServiceImplTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private JdbcTemplate jdbcTemplate;
    private HolidayReadPlatformServiceImpl underTest;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        underTest = new HolidayReadPlatformServiceImpl(mock(PlatformSecurityContext.class), jdbcTemplate);
    }

    /** PostgreSQL does not compare a date column with text: the range must reach the query as dates. */
    @Test
    void aDateRangeIsBoundAsDates() {
        underTest.retrieveAllHolidaysBySearchParamerters(1L, FROM, TO);

        assertArrayEquals(new Object[] { 1L, FROM, TO }, boundArguments());
    }

    @Test
    void withoutARangeOnlyTheOfficeIsBound() {
        underTest.retrieveAllHolidaysBySearchParamerters(1L, null, null);

        assertArrayEquals(new Object[] { 1L }, boundArguments());
    }

    @Test
    void anOpenEndedRangeBindsOnlyTheDateGiven() {
        underTest.retrieveAllHolidaysBySearchParamerters(1L, null, TO);

        assertArrayEquals(new Object[] { 1L, TO }, boundArguments());
    }

    @SuppressWarnings("unchecked")
    private Object[] boundArguments() {
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), arguments.capture());
        return arguments.getValue();
    }
}
