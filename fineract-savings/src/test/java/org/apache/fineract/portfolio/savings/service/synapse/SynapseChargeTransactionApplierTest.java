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
package org.apache.fineract.portfolio.savings.service.synapse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseChargeTransactionApplier.ReplayResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SynapseChargeTransactionApplierTest {

    private static MockedStatic<MoneyHelper> moneyHelper;

    @Mock
    private SavingsAccountTransactionRepository transactionRepository;

    private final SavingsAccountTransactionSummaryWrapper summaryWrapper = new SavingsAccountTransactionSummaryWrapper();
    private SynapseChargeTransactionApplier applier;

    @BeforeAll
    static void initMoney() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void closeMoney() {
        moneyHelper.close();
    }

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 3, 20))));
        applier = new SynapseChargeTransactionApplier(transactionRepository, summaryWrapper);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Nested
    class ChargePosting {

        @Test
        void createsPayChargeTransaction_debitsBalance_linksCharge() throws Exception {
            SavingsAccount account = buildAccount(1L, new BigDecimal("1000.00"));
            SavingsAccountCharge charge = addCharge(account, 10L);
            when(transactionRepository.findByRefNo("trace-c1")).thenReturn(Collections.emptyList());

            ReplayResult result = applier.replay(account, new BigDecimal("75.00"),
                    LocalDate.of(2026, 3, 20), 10L, "trace-c1");

            assertThat(result.alreadyExists()).isFalse();
            assertThat(result.transaction().getTypeOf()).isEqualTo(SavingsAccountTransactionType.PAY_CHARGE.getValue());
            assertThat(result.transaction().getRefNo()).isEqualTo("trace-c1");
            assertThat(result.transaction().getAmount()).isEqualByComparingTo("75.00");
            assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo("925.00");
            assertThat(result.transaction().getRunningBalance()).isEqualByComparingTo("925.00");
            assertThat(result.transaction().getSavingsAccountChargesPaid()).hasSize(1);
            assertThat(result.transaction().getSavingsAccountChargesPaid().iterator().next().getSavingsAccountCharge())
                    .isSameAs(charge);
        }
    }

    @Nested
    class Idempotency {

        @Test
        void duplicateTraceId_returnsExistingTransaction() throws Exception {
            SavingsAccount account = buildAccount(2L, new BigDecimal("1000.00"));
            addCharge(account, 20L);
            SavingsAccountTransaction existingTx = SavingsAccountTransaction.charge(account, account.office(),
                    LocalDate.of(2026, 3, 19), Money.of(account.getCurrency(), new BigDecimal("50.00")));
            when(transactionRepository.findByRefNo("trace-dup")).thenReturn(List.of(existingTx));

            ReplayResult result = applier.replay(account, new BigDecimal("50.00"),
                    LocalDate.of(2026, 3, 20), 20L, "trace-dup");

            assertThat(result.alreadyExists()).isTrue();
            assertThat(result.transaction()).isSameAs(existingTx);
            assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo("1000.00");
        }
    }

    @Nested
    class ChargeNotFound {

        @Test
        void throwsWhenChargeIdDoesNotMatchAnyChargeOnAccount() throws Exception {
            SavingsAccount account = buildAccount(3L, new BigDecimal("1000.00"));
            addCharge(account, 30L);
            when(transactionRepository.findByRefNo("trace-bad")).thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> applier.replay(account, new BigDecimal("10.00"),
                    LocalDate.of(2026, 3, 20), 999L, "trace-bad"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("999");
        }
    }

    private static SavingsAccount buildAccount(Long id, BigDecimal balance) throws Exception {
        MonetaryCurrency ngn = new MonetaryCurrency("NGN", 2, null);

        Office office = Office.headOffice("HQ", LocalDate.of(2020, 1, 1), null);
        setField(AbstractPersistableCustom.class, office, "id", 1L);

        Client client = new Client() {};
        client.setOffice(office);
        setField(AbstractPersistableCustom.class, client, "id", id);

        java.lang.reflect.Constructor<SavingsAccountSummary> ctor = SavingsAccountSummary.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        SavingsAccountSummary summary = ctor.newInstance();
        summary.setAccountBalance(balance);

        SavingsAccount account = new SavingsAccount() {};
        setField(AbstractPersistableCustom.class, account, "id", id);
        setField(SavingsAccount.class, account, "currency", ngn);
        setField(SavingsAccount.class, account, "client", client);
        setField(SavingsAccount.class, account, "summary", summary);
        setField(SavingsAccount.class, account, "charges", new HashSet<SavingsAccountCharge>());

        return account;
    }

    private static SavingsAccountCharge addCharge(SavingsAccount account, Long chargeId) throws Exception {
        SavingsAccountCharge charge = new SavingsAccountCharge() {};
        setField(AbstractPersistableCustom.class, charge, "id", chargeId);
        account.charges().add(charge);
        return charge;
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
