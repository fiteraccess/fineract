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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import sun.misc.Unsafe;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.exception.PlatformServiceUnavailableException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.service.synapse.InterestPostingReplayService;
import org.apache.fineract.portfolio.savings.service.synapse.InterestPostingReplayService.ReplayResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SavingsAccountWritePlatformServiceReplayInterestPostingTest {

    private SavingsAccountWritePlatformServiceJpaRepositoryImpl service;

    private ObjectProvider<InterestPostingReplayService> replayServiceProvider;
    private InterestPostingReplayService replayService;
    private SavingsAccountAssembler assembler;
    private SavingsAccountTransactionRepository txRepo;
    private SavingsAccountRepositoryWrapper accountRepo;
    private JournalEntryWritePlatformService journalService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        replayServiceProvider = mock(ObjectProvider.class);
        replayService = mock(InterestPostingReplayService.class);
        assembler = mock(SavingsAccountAssembler.class);
        txRepo = mock(SavingsAccountTransactionRepository.class);
        accountRepo = mock(SavingsAccountRepositoryWrapper.class);
        journalService = mock(JournalEntryWritePlatformService.class);

        service = createServiceWithReflection();
    }

    @Test
    void happyPath_delegatesSavesAndPostsJournalEntries() throws Exception {
        Long savingsId = 10L;
        SavingsAccount account = buildAccount(savingsId);
        SavingsAccountTransaction tx = mock(SavingsAccountTransaction.class);
        when(tx.getId()).thenReturn(99L);

        when(replayServiceProvider.getIfAvailable()).thenReturn(replayService);
        when(assembler.assembleFrom(savingsId, false)).thenReturn(account);
        when(replayService.replay(eq(account), eq("INTEREST_POSTING"), eq(new BigDecimal("100.00")),
                eq(LocalDate.of(2026, 3, 20)), eq(null), eq("trace-1")))
                .thenReturn(new ReplayResult(tx, false));

        JsonCommand command = mockCommand(LocalDate.of(2026, 3, 20), new BigDecimal("100.00"),
                "INTEREST_POSTING", "trace-1", null);

        CommandProcessingResult result = service.replayInterestPosting(savingsId, command);

        assertThat(result.getSavingsId()).isEqualTo(savingsId);
        verify(txRepo).saveAndFlush(tx);
        verify(accountRepo).saveAndFlush(account);
        verify(journalService).createJournalEntriesForSavings(any());
    }

    @Test
    void idempotentReplay_returnsEarlyWithoutSaving() throws Exception {
        Long savingsId = 11L;
        SavingsAccount account = buildAccount(savingsId);
        SavingsAccountTransaction existingTx = mock(SavingsAccountTransaction.class);
        when(existingTx.getId()).thenReturn(88L);

        when(replayServiceProvider.getIfAvailable()).thenReturn(replayService);
        when(assembler.assembleFrom(savingsId, false)).thenReturn(account);
        when(replayService.replay(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReplayResult(existingTx, true));

        JsonCommand command = mockCommand(LocalDate.of(2026, 3, 20), new BigDecimal("50.00"),
                "INTEREST_POSTING", "trace-dup", null);

        CommandProcessingResult result = service.replayInterestPosting(savingsId, command);

        assertThat(result.getSavingsId()).isEqualTo(savingsId);
        assertThat(result.getResourceId()).isEqualTo(88L);
        verify(txRepo, never()).saveAndFlush(any());
        verify(accountRepo, never()).saveAndFlush(any(SavingsAccount.class));
        verify(journalService, never()).createJournalEntriesForSavings(any());
    }

    @Test
    void synapseDisabled_throwsPlatformServiceUnavailable() {
        when(replayServiceProvider.getIfAvailable()).thenReturn(null);

        JsonCommand command = mockCommand(LocalDate.of(2026, 3, 20), new BigDecimal("10.00"),
                "INTEREST_POSTING", "trace-x", null);

        assertThatThrownBy(() -> service.replayInterestPosting(1L, command))
                .isInstanceOf(PlatformServiceUnavailableException.class);
    }

    private JsonCommand mockCommand(LocalDate date, BigDecimal amount, String type, String traceId, BigDecimal overdraft) {
        JsonCommand cmd = mock(JsonCommand.class);
        when(cmd.localDateValueOfParameterNamed("transactionDate")).thenReturn(date);
        when(cmd.bigDecimalValueOfParameterNamed("transactionAmount")).thenReturn(amount);
        when(cmd.stringValueOfParameterNamed("transactionType")).thenReturn(type);
        when(cmd.stringValueOfParameterNamed("traceId")).thenReturn(traceId);
        when(cmd.bigDecimalValueOfParameterNamed("overdraftAmount")).thenReturn(overdraft);
        return cmd;
    }

    private SavingsAccount buildAccount(Long id) throws Exception {
        MonetaryCurrency ngn = new MonetaryCurrency("NGN", 2, null);
        Office office = Office.headOffice("HQ", LocalDate.of(2020, 1, 1), null);
        setField(AbstractPersistableCustom.class, office, "id", 1L);
        Client client = new Client() {};
        client.setOffice(office);
        setField(AbstractPersistableCustom.class, client, "id", id);
        SavingsProduct product = new SavingsProduct() {};
        setField(AbstractPersistableCustom.class, product, "id", 1L);
        setField(SavingsProduct.class, product, "accountingRule", 1);

        SavingsAccount account = new SavingsAccount() {};
        setField(AbstractPersistableCustom.class, account, "id", id);
        setField(SavingsAccount.class, account, "currency", ngn);
        setField(SavingsAccount.class, account, "client", client);
        setField(SavingsAccount.class, account, "product", product);
        return account;
    }


    @SuppressWarnings("restriction")
    private SavingsAccountWritePlatformServiceJpaRepositoryImpl createServiceWithReflection() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        SavingsAccountWritePlatformServiceJpaRepositoryImpl svc =
                (SavingsAccountWritePlatformServiceJpaRepositoryImpl) unsafe.allocateInstance(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class);
        setField(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class, svc, "interestPostingReplayServiceProvider", replayServiceProvider);
        setField(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class, svc, "savingAccountAssembler", assembler);
        setField(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class, svc, "savingsAccountTransactionRepository", txRepo);
        setField(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class, svc, "savingAccountRepositoryWrapper", accountRepo);
        setField(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class, svc, "journalEntryWritePlatformService", journalService);
        return svc;
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
