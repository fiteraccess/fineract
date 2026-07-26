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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsAccountTransactionNipPrimitiveTest {

    @BeforeEach
    void setBusinessDate() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 7, 25))));
    }

    @AfterEach
    void resetThreadLocalContext() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void commissionFactoryCarriesSwitchIdentityIntoAccountingBridge() {
        SavingsAccount account = mock(SavingsAccount.class);
        Office office = mock(Office.class);
        Money amount = mock(Money.class);
        when(office.getId()).thenReturn(12L);
        when(amount.getAmount()).thenReturn(BigDecimal.valueOf(22));

        SavingsAccountTransaction transaction = SavingsAccountTransaction.commission(account, office, LocalDate.of(2026, 7, 25), amount,
                "root-reference", "NIBSS");

        assertThat(transaction.getTransactionType()).isEqualTo(SavingsAccountTransactionType.COMMISSION);
        assertThat(transaction.getSwitchId()).isEqualTo("NIBSS");
        assertThat(transaction.getRefNo()).isEqualTo("root-reference");
        assertThat(transaction.toAccountingBridgeDTO("NGN").getSwitchId()).isEqualTo("NIBSS");
    }

    @Test
    void vatFactoryAndJpaMappingUseNullableSwitchColumn() throws NoSuchFieldException {
        SavingsAccountTransaction transaction = SavingsAccountTransaction.vat(mock(SavingsAccount.class), mock(Office.class),
                LocalDate.of(2026, 7, 25), mock(Money.class), "root-reference", "HYDROGEN");
        Column column = SavingsAccountTransaction.class.getDeclaredField("switchId").getAnnotation(Column.class);

        assertThat(transaction.getTransactionType()).isEqualTo(SavingsAccountTransactionType.VAT);
        assertThat(transaction.getSwitchId()).isEqualTo("HYDROGEN");
        assertThat(column.name()).isEqualTo("switch_id");
        assertThat(column.nullable()).isTrue();
    }
}
