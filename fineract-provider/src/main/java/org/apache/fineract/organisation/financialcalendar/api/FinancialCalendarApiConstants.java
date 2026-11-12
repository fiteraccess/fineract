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
package org.apache.fineract.organisation.financialcalendar.api;

import java.util.Set;

public final class FinancialCalendarApiConstants {

    public static final String RESOURCE_NAME_FOR_PERMISSION = "FINANCIALYEAR";
    public static final String DESCRIPTION = "description";
    public static final String START_DATE = "startDate";
    public static final String END_DATE = "endDate";
    public static final String HOLIDAYS_CONFIRMED = "holidaysConfirmed";
    public static final String PERIODS = "periods";
    public static final String PERIOD_CODE = "periodCode";
    public static final Set<String> UPSERT_PARAMETERS = Set.of(DESCRIPTION, START_DATE, END_DATE, HOLIDAYS_CONFIRMED, PERIODS);
    public static final Set<String> PERIOD_PARAMETERS = Set.of(PERIOD_CODE, START_DATE, END_DATE);

    private FinancialCalendarApiConstants() {}
}
