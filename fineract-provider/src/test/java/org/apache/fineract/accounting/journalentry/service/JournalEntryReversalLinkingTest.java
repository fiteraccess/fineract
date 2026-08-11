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
package org.apache.fineract.accounting.journalentry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.junit.jupiter.api.Test;

class JournalEntryReversalLinkingTest {

    private static final long SAVINGS_CONTROL_GL = 20L;
    private static final long FUND_SOURCE_GL = 10L;

    @Test
    void pairsEachBookingEntryWithItsLaterOppositeEntry() {
        JournalEntry bookingDebit = entry(1L, FUND_SOURCE_GL, "100", true);
        JournalEntry bookingCredit = entry(2L, SAVINGS_CONTROL_GL, "100", false);
        JournalEntry reversalCredit = entry(11L, FUND_SOURCE_GL, "100", false);
        JournalEntry reversalDebit = entry(12L, SAVINGS_CONTROL_GL, "100", true);

        List<JournalEntry> linked = JournalEntryWritePlatformServiceJpaRepositoryImpl
                .linkReversalPairs(List.of(bookingDebit, bookingCredit, reversalCredit, reversalDebit));

        assertThat(linked).containsExactly(bookingDebit, bookingCredit);
        verify(bookingDebit).setReversed(true);
        verify(bookingDebit).setReversalJournalEntry(reversalCredit);
        verify(bookingCredit).setReversed(true);
        verify(bookingCredit).setReversalJournalEntry(reversalDebit);
        verify(reversalCredit, never()).setReversed(true);
        verify(reversalDebit, never()).setReversed(true);
    }

    @Test
    void pairsTwoLegCommissionSplitIndependentlyPerGlAccount() {
        JournalEntry bookingSwitchFee = entry(1L, 30L, "15", false);
        JournalEntry bookingBankCommission = entry(2L, 31L, "7", false);
        JournalEntry reversalSwitchFee = entry(11L, 30L, "15", true);
        JournalEntry reversalBankCommission = entry(12L, 31L, "7", true);

        List<JournalEntry> linked = JournalEntryWritePlatformServiceJpaRepositoryImpl
                .linkReversalPairs(List.of(bookingSwitchFee, bookingBankCommission, reversalSwitchFee, reversalBankCommission));

        assertThat(linked).containsExactly(bookingSwitchFee, bookingBankCommission);
        verify(bookingSwitchFee).setReversalJournalEntry(reversalSwitchFee);
        verify(bookingBankCommission).setReversalJournalEntry(reversalBankCommission);
    }

    @Test
    void leavesUnmatchedEntriesUntouched() {
        JournalEntry bookingDebit = entry(1L, FUND_SOURCE_GL, "100", true);
        JournalEntry unrelatedCredit = entry(11L, SAVINGS_CONTROL_GL, "100", false);

        List<JournalEntry> linked = JournalEntryWritePlatformServiceJpaRepositoryImpl
                .linkReversalPairs(List.of(bookingDebit, unrelatedCredit));

        assertThat(linked).isEmpty();
        verify(bookingDebit, never()).setReversed(true);
        verify(unrelatedCredit, never()).setReversed(true);
    }

    @Test
    void skipsEntriesAlreadyReversedOrLinked() {
        JournalEntry alreadyReversed = entry(1L, FUND_SOURCE_GL, "100", true);
        lenient().when(alreadyReversed.isReversed()).thenReturn(true);
        JournalEntry priorReversal = entry(2L, FUND_SOURCE_GL, "100", false);

        List<JournalEntry> linked = JournalEntryWritePlatformServiceJpaRepositoryImpl
                .linkReversalPairs(List.of(alreadyReversed, priorReversal));

        assertThat(linked).isEmpty();
        verify(priorReversal, never()).setReversed(true);
    }

    private JournalEntry entry(final Long id, final Long glAccountId, final String amount, final boolean debit) {
        JournalEntry entry = mock(JournalEntry.class);
        GLAccount glAccount = mock(GLAccount.class);
        lenient().when(glAccount.getId()).thenReturn(glAccountId);
        lenient().when(entry.getId()).thenReturn(id);
        lenient().when(entry.getGlAccount()).thenReturn(glAccount);
        lenient().when(entry.getAmount()).thenReturn(new BigDecimal(amount));
        lenient().when(entry.isDebitEntry()).thenReturn(debit);
        lenient().when(entry.isReversed()).thenReturn(false);
        lenient().when(entry.getReversalJournalEntry()).thenReturn(null);
        return entry;
    }
}
