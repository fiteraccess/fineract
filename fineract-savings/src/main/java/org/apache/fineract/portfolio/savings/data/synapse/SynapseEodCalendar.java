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
package org.apache.fineract.portfolio.savings.data.synapse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Synapse's answer to {@code GET /api/v1/proxy/eod/calendar?closing=D&next=N}: the financial period and year covering
 * each day (null when none does) and what stops EOD from rolling into every calendar year entered between D and N.
 * Financial years and periods live in Synapse.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseEodCalendar {

    private LocalDate closing;
    private LocalDate next;
    private Period closingPeriod;
    private Year closingYear;
    private Period nextPeriod;
    private Year nextYear;
    private List<String> yearReadinessProblems;

    public List<String> getYearReadinessProblems() {
        return yearReadinessProblems == null ? List.of() : yearReadinessProblems;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Period {

        private String financialCycle;
        private String periodCode;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Year {

        private String financialCycle;
        private LocalDate startDate;
        private LocalDate endDate;
        private boolean holidaysConfirmed;
        private LocalDate closedOn;
    }
}
