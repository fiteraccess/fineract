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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_financial_period")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialPeriod extends AbstractPersistableCustom<Long> {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "financial_year_id", nullable = false)
    private FinancialYear financialYear;

    @Column(name = "period_code", nullable = false, length = 10)
    private String periodCode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    public static FinancialPeriod create(FinancialYear financialYear, String periodCode, LocalDate startDate, LocalDate endDate) {
        FinancialPeriod period = new FinancialPeriod();
        period.financialYear = financialYear;
        period.periodCode = periodCode;
        period.startDate = startDate;
        period.endDate = endDate;
        return period;
    }

    public void moveTo(LocalDate startDate, LocalDate endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
