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
package org.apache.fineract.infrastructure.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThreadLocalContextUtilTest {

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    void setBusinessDatesNormalizesStringKeyedMap() {
        HashMap rawBusinessDates = new HashMap();
        rawBusinessDates.put("BUSINESS_DATE", "2026-03-02");
        rawBusinessDates.put("COB_DATE", LocalDate.of(2026, 3, 1));

        ThreadLocalContextUtil.setBusinessDates(rawBusinessDates);

        assertEquals(LocalDate.of(2026, 3, 2), ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
        assertEquals(LocalDate.of(2026, 3, 1), ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE));
    }

    @Test
    void getBusinessDatesReturnsDefensiveCopy() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(java.util.Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 3, 2))));

        HashMap<BusinessDateType, LocalDate> retrievedBusinessDates = ThreadLocalContextUtil.getBusinessDates();
        retrievedBusinessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 3, 7));

        assertEquals(LocalDate.of(2026, 3, 2), ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.BUSINESS_DATE));
    }
}
