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

import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.service.synapse.InterestPostingReplayService.ReplayResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterestPostingReplayServiceTest {

    private static MockedStatic<MoneyHelper> moneyHelper;

    @Mock
    private SavingsAccountTransactionRepository transactionRepository;

    private final SavingsAccountTransactionSummaryWrapper summaryWrapper = new SavingsAccountTransactionSummaryWrapper();

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
    void setBusinessDate() {
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 3, 20))));
    }

    @AfterEach
    void clearBusinessDate() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void interestPosting_createsTransaction_andCreditsBalance() throws Exception {
        SavingsAccount account = buildAccount(1L, new BigDecimal("1000.00"));
        when(transactionRepository.findByRefNo("trace-1")).thenReturn(Collections.emptyList());

        InterestPostingReplayService service = new InterestPostingReplayService(transactionRepository, summaryWrapper);
        ReplayResult result = service.replay(account, "INTEREST_POSTING", new BigDecimal("250.00"),
                LocalDate.of(2026, 3, 20), null, "trace-1");

        assertThat(result.alreadyExists()).isFalse();
        assertThat(result.transaction().getTypeOf()).isEqualTo(SavingsAccountTransactionType.INTEREST_POSTING.getValue());
        assertThat(result.transaction().getRefNo()).isEqualTo("trace-1");
        assertThat(result.transaction().getAmount()).isEqualByComparingTo("250.00");
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo("1250.00");
        assertThat(result.transaction().getRunningBalance()).isEqualByComparingTo("1250.00");
    }

    @Test
    void overdraftInterest_createsTransaction_andDebitsBalance() throws Exception {
        SavingsAccount account = buildAccount(2L, new BigDecimal("1000.00"));
        when(transactionRepository.findByRefNo("trace-2")).thenReturn(Collections.emptyList());

        InterestPostingReplayService service = new InterestPostingReplayService(transactionRepository, summaryWrapper);
        ReplayResult result = service.replay(account, "OVERDRAFT_INTEREST", new BigDecimal("50.00"),
                LocalDate.of(2026, 3, 20), new BigDecimal("300.00"), "trace-2");

        assertThat(result.alreadyExists()).isFalse();
        assertThat(result.transaction().getTypeOf()).isEqualTo(SavingsAccountTransactionType.OVERDRAFT_INTEREST.getValue());
        assertThat(result.transaction().getOverdraftAmount(account.getCurrency()).getAmount()).isEqualByComparingTo("300.00");
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo("950.00");
    }

    @Test
    void withholdTax_createsTransaction_andDebitsBalance() throws Exception {
        SavingsAccount account = buildAccount(3L, new BigDecimal("1000.00"));
        when(transactionRepository.findByRefNo("trace-3")).thenReturn(Collections.emptyList());

        InterestPostingReplayService service = new InterestPostingReplayService(transactionRepository, summaryWrapper);
        ReplayResult result = service.replay(account, "WITHHOLD_TAX", new BigDecimal("25.00"),
                LocalDate.of(2026, 3, 20), null, "trace-3");

        assertThat(result.alreadyExists()).isFalse();
        assertThat(result.transaction().getTypeOf()).isEqualTo(SavingsAccountTransactionType.WITHHOLD_TAX.getValue());
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo("975.00");
    }

    @Test
    void duplicateTraceId_returnsExistingTransaction() throws Exception {
        SavingsAccount account = buildAccount(4L, new BigDecimal("1000.00"));
        SavingsAccountTransaction existingTx = SavingsAccountTransaction.interestPosting(account, account.office(),
                LocalDate.of(2026, 3, 19),
                org.apache.fineract.organisation.monetary.domain.Money.of(account.getCurrency(), new BigDecimal("100.00")), false);
        when(transactionRepository.findByRefNo("trace-dup")).thenReturn(List.of(existingTx));

        InterestPostingReplayService service = new InterestPostingReplayService(transactionRepository, summaryWrapper);
        ReplayResult result = service.replay(account, "INTEREST_POSTING", new BigDecimal("250.00"),
                LocalDate.of(2026, 3, 20), null, "trace-dup");

        assertThat(result.alreadyExists()).isTrue();
        assertThat(result.transaction()).isSameAs(existingTx);
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void unknownTransactionType_throwsIllegalArgument() throws Exception {
        SavingsAccount account = buildAccount(5L, new BigDecimal("1000.00"));
        when(transactionRepository.findByRefNo("trace-bad")).thenReturn(Collections.emptyList());

        InterestPostingReplayService service = new InterestPostingReplayService(transactionRepository, summaryWrapper);

        assertThatThrownBy(() -> service.replay(account, "INVALID_TYPE", new BigDecimal("10.00"),
                LocalDate.of(2026, 3, 20), null, "trace-bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_TYPE");
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

        return account;
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
