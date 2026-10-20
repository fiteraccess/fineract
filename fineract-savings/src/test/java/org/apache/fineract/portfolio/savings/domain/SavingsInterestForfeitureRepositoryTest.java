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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository.Transition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class SavingsInterestForfeitureRepositoryTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM m_savings_interest_forfeiture WHERE savings_account_id = ? AND governing_month = ?";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SavingsInterestForfeitureRepository repository = new SavingsInterestForfeitureRepository(jdbcTemplate);
    private final Transition transition = new Transition(7L, OCTOBER, true, 5L, "3f6c1a52-4c1e-4a0b-9a77-2f1f0b8f2b11");
    private MockedStatic<DateUtils> dateUtils;

    @BeforeEach
    void freezeClock() {
        dateUtils = Mockito.mockStatic(DateUtils.class);
        dateUtils.when(DateUtils::getAuditLocalDateTime).thenReturn(LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    @AfterEach
    void releaseClock() {
        dateUtils.close();
    }

    @Test
    void aNewerTransitionReplacesTheStoredOne() {
        when(jdbcTemplate.update(startsWith("UPDATE m_savings_interest_forfeiture"), any(Object[].class))).thenReturn(1);

        assertThat(repository.applyIfNewer(transition)).isTrue();

        verify(jdbcTemplate, never()).update(startsWith("INSERT"), any(Object[].class));
    }

    @Test
    void aStaleOrDuplicateSequenceIsIgnored() {
        when(jdbcTemplate.update(startsWith("UPDATE m_savings_interest_forfeiture"), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(eq(COUNT_SQL), eq(Integer.class), eq(7L), eq(OCTOBER))).thenReturn(1);

        assertThat(repository.applyIfNewer(transition)).isFalse();

        verify(jdbcTemplate, never()).update(startsWith("INSERT"), any(Object[].class));
    }

    @Test
    void theFirstTransitionOfAMonthIsInserted() {
        when(jdbcTemplate.update(startsWith("UPDATE m_savings_interest_forfeiture"), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(eq(COUNT_SQL), eq(Integer.class), eq(7L), eq(OCTOBER))).thenReturn(0);

        assertThat(repository.applyIfNewer(transition)).isTrue();

        verify(jdbcTemplate).update(startsWith("INSERT INTO m_savings_interest_forfeiture"), eq(7L), eq(OCTOBER), eq(true), eq(5L),
                anyString(), any());
    }
}
