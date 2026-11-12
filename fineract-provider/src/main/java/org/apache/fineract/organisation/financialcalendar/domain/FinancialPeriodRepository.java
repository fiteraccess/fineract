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
package org.apache.fineract.organisation.financialcalendar.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialPeriodRepository extends JpaRepository<FinancialPeriod, Long> {

    List<FinancialPeriod> findByFinancialYearOrderByStartDateAsc(FinancialYear financialYear);

    @Query("select p from FinancialPeriod p where p.startDate <= :date and p.endDate >= :date")
    Optional<FinancialPeriod> findCovering(@Param("date") LocalDate date);

    /** Savings transactions (reversals excluded) dated inside a period: what deleting the period would orphan. */
    @Query(value = "SELECT COUNT(1) FROM m_savings_account_transaction WHERE transaction_date BETWEEN ?1 AND ?2"
            + " AND is_reversed = false", nativeQuery = true)
    long countPostedTransactionsBetween(LocalDate startDate, LocalDate endDate);
}
