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
package org.apache.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.savings.SavingsProductCategory;
import org.apache.fineract.portfolio.savings.data.SavingsAccrualData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** AB-566: end-of-day accrual of a Regular Savings month Synapse forfeits, restores and forfeits again. */
class SavingsAccrualWritePlatformServiceImplForfeitureTest {

    private static final Long ID = 77L;
    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, null);
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    private final SavingsAccountReadPlatformService readService = mock(SavingsAccountReadPlatformService.class);
    private final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
    private final ConfigurationDomainService configuration = mock(ConfigurationDomainService.class);
    private final SavingsAccountDomainService domainService = mock(SavingsAccountDomainService.class);
    private final SavingsInterestForfeitureRepository forfeitureRepository = mock(SavingsInterestForfeitureRepository.class);
    private final JournalEntryWritePlatformService journalService = mock(JournalEntryWritePlatformService.class);
    private final SavingsAccrualWritePlatformServiceImpl service = new SavingsAccrualWritePlatformServiceImpl(readService, assembler,
            mock(SavingsAccountRepositoryWrapper.class), configuration, domainService, mock(PlatformTransactionManager.class),
            forfeitureRepository, journalService);
    private final Office office = mock(Office.class);
    private long nextId = 100L;

    @BeforeEach
    void setUp() throws Exception {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Africa/Lagos", null));
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());
        setBusinessDate(LocalDate.of(2026, 10, 1));
        when(configuration.retrieveFinancialYearBeginningMonth()).thenReturn(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCache();
    }

    @Test
    void aForfeitedMonthReversesItsAccrualsWithJournalsAndBooksNothing() throws Exception {
        SavingsAccount account = account();
        List<SavingsAccountTransaction> booked = accrueDays(account, OCTOBER, 4);

        run(account, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5), Map.of(OCTOBER, true));

        assertThat(liveAccruals(account)).isEmpty();
        assertThat(account.getAccruedTillDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Long>> reversedBefore = ArgumentCaptor.forClass(Set.class);
        verify(domainService).postJournalEntries(eq(account), anySet(), reversedBefore.capture(), eq(false));
        assertThat(reversedBefore.getValue()).doesNotContainAnyElementsOf(ids(booked));
        verify(journalService).linkSavingsReversalJournalEntries(ids(booked));
    }

    @Test
    void restoringTheMonthRebooksItsInterestToDate() throws Exception {
        SavingsAccount account = account();
        accrueDays(account, OCTOBER, 4).forEach(SavingsAccountTransaction::reverse);
        LocalDate till = LocalDate.of(2026, 10, 5);

        run(account, LocalDate.of(2026, 10, 4), till, Map.of(OCTOBER, false));

        assertThat(liveAccruals(account)).extracting(SavingsAccountTransaction::getTransactionDate)
                .containsExactly(LocalDate.of(2026, 10, 4), till);
        assertThat(total(liveAccruals(account))).isEqualByComparingTo(earnedInMonthTo(account, till));
        verify(journalService, never()).linkSavingsReversalJournalEntries(any());
    }

    @Test
    void reForfeitingAfterARestoreReversesTheRestoredInterestToo() throws Exception {
        SavingsAccount account = account();
        accrueDays(account, OCTOBER, 3).forEach(SavingsAccountTransaction::reverse);
        SavingsAccountTransaction restored = accrue(account, LocalDate.of(2026, 10, 4), "131.51");

        run(account, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5), Map.of(OCTOBER, true));

        assertThat(liveAccruals(account)).isEmpty();
        verify(journalService).linkSavingsReversalJournalEntries(List.of(restored.getId()));
    }

    @Test
    void crossingAndComingBackBeforeEndOfDayChangesNothing() throws Exception {
        SavingsAccount account = account();
        List<SavingsAccountTransaction> booked = accrueDays(account, OCTOBER, 4);

        run(account, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5), Map.of(OCTOBER, false));

        assertThat(booked).noneMatch(SavingsAccountTransaction::isReversed);
        assertThat(liveAccruals(account)).hasSize(5).last().extracting(SavingsAccountTransaction::getTransactionDate)
                .isEqualTo(LocalDate.of(2026, 10, 5));
        verify(journalService, never()).linkSavingsReversalJournalEntries(any());
    }

    @Test
    void aForfeitedMonthLeavesTheNextMonthAccruing() throws Exception {
        SavingsAccount account = account();
        List<SavingsAccountTransaction> september = accrueDays(account, LocalDate.of(2026, 9, 28), 2);

        run(account, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2), Map.of(SEPTEMBER, true));

        assertThat(september).allMatch(SavingsAccountTransaction::isReversed);
        assertThat(liveAccruals(account)).extracting(SavingsAccountTransaction::getTransactionDate)
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
    }

    @Test
    void aMonthRestoredAfterItsLastEndOfDayIsRebookedOnItsLastDay() throws Exception {
        SavingsAccount account = account();
        accrueDays(account, LocalDate.of(2026, 9, 29), 2).forEach(SavingsAccountTransaction::reverse);
        LocalDate septemberEnd = LocalDate.of(2026, 9, 30);

        run(account, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), Map.of(SEPTEMBER, false));

        assertThat(liveAccruals(account)).extracting(SavingsAccountTransaction::getTransactionDate).containsExactly(septemberEnd,
                LocalDate.of(2026, 10, 1));
        assertThat(liveAccruals(account).get(0).getAmount()).isEqualByComparingTo(earnedInMonthTo(account, septemberEnd));
    }

    @Test
    void theRunLoadsForfeituresOnceForAllAccounts() throws Exception {
        LocalDate till = LocalDate.of(2026, 10, 5);
        setBusinessDate(till);
        when(readService.retrievePeriodicAccrualData(till, null)).thenReturn(List.of(
                new SavingsAccrualData(1L, "0001", till, false, false, 100), new SavingsAccrualData(2L, "0002", till, false, false, 100)));
        when(assembler.assembleFrom(any(), anyBoolean())).thenAnswer(invocation -> account());

        service.addAccrualEntries(till);

        verify(forfeitureRepository, times(1)).findStatesBetween(SEPTEMBER, till);
    }

    private void run(final SavingsAccount account, final LocalDate accruedTill, final LocalDate till, final Map<LocalDate, Boolean> states)
            throws Exception {
        setBusinessDate(till);
        account.setAccruedTillDate(accruedTill);
        when(readService.retrievePeriodicAccrualData(till, null))
                .thenReturn(List.of(new SavingsAccrualData(ID, "000" + ID, accruedTill, false, false, 100)));
        when(assembler.assembleFrom(ID, false)).thenReturn(account);
        when(forfeitureRepository.findStatesBetween(any(), eq(till))).thenReturn(Map.of(ID, new TreeMap<>(states)));
        service.addAccrualEntries(till);
    }

    private static void setBusinessDate(final LocalDate date) {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    }

    private List<SavingsAccountTransaction> accrueDays(final SavingsAccount account, final LocalDate from, final int days) {
        return from.datesUntil(from.plusDays(days)).map(day -> accrue(account, day, "30.00")).toList();
    }

    private SavingsAccountTransaction accrue(final SavingsAccount account, final LocalDate day, final String amount) {
        SavingsAccountTransaction accrual = SavingsAccountTransaction.accrual(account, office, day, Money.of(NGN, new BigDecimal(amount)),
                false, null);
        ReflectionTestUtils.setField(accrual, "id", nextId++);
        account.addTransaction(accrual);
        return accrual;
    }

    private static List<SavingsAccountTransaction> liveAccruals(final SavingsAccount account) {
        return account.retrieveOrderedAccrualTransactions().stream().filter(accrual -> !accrual.isReversed()).toList();
    }

    private static List<Long> ids(final List<SavingsAccountTransaction> transactions) {
        return transactions.stream().map(SavingsAccountTransaction::getId).toList();
    }

    private static BigDecimal total(final List<SavingsAccountTransaction> transactions) {
        return transactions.stream().map(SavingsAccountTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal earnedInMonthTo(final SavingsAccount account, final LocalDate day) {
        PostingPeriod period = account.projectInterestUsing(MoneyHelper.getMathContext(), day, false, 1).stream()
                .filter(candidate -> candidate.getPeriodInterval().contains(day)).findFirst().orElseThrow();
        return period.getInterestEarned().getAmount();
    }

    private SavingsAccount account() {
        SavingsAccount account = BeanUtils.instantiateClass(SavingsAccount.class);
        SavingsProduct product = mock(SavingsProduct.class);
        when(product.getProductCategory()).thenReturn(SavingsProductCategory.DIGITAL);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "product", product);
        ReflectionTestUtils.setField(account, "currency", NGN);
        ReflectionTestUtils.setField(account, "activatedOnDate", SEPTEMBER);
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRate", BigDecimal.valueOf(12));
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRateOverdraft", BigDecimal.ZERO);
        ReflectionTestUtils.setField(account, "minOverdraftForInterestCalculation", BigDecimal.ZERO);
        ReflectionTestUtils.setField(account, "interestCompoundingPeriodType", 1);
        ReflectionTestUtils.setField(account, "interestPostingPeriodType", 4);
        ReflectionTestUtils.setField(account, "interestCalculationType", 1);
        ReflectionTestUtils.setField(account, "interestCalculationDaysInYearType", 365);
        ReflectionTestUtils.setField(account, "summary",
                BeanUtils.instantiateClass(org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary.class));
        account.setHelpers(new SavingsAccountTransactionSummaryWrapper(),
                new SavingsHelper(mock(AccountTransfersReadPlatformService.class)));
        SavingsAccountTransaction deposit = SavingsAccountTransaction.deposit(account, office, null, SEPTEMBER,
                Money.of(NGN, BigDecimal.valueOf(100_000)), null);
        ReflectionTestUtils.setField(deposit, "id", nextId++);
        account.addTransaction(deposit);
        return account;
    }
}
