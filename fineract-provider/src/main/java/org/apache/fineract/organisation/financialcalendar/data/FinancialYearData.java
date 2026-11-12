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
package org.apache.fineract.organisation.financialcalendar.data;

import java.util.List;

/**
 * A financial year as the API shows it; dates are ISO yyyy-MM-dd, as the API accepts them. {@code closedOn} is the
 * business date of the EOD run that closed the year, null while it is open.
 */
public record FinancialYearData(String financialCycle, String description, String startDate, String endDate, boolean holidaysConfirmed,
        String closedOn, List<FinancialPeriodData> periods) {

    public record FinancialPeriodData(String periodCode, String startDate, String endDate) {
    }
}
