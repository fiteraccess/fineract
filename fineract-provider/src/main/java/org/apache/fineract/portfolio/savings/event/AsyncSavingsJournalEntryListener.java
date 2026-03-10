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
package org.apache.fineract.portfolio.savings.event;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link SavingsJournalEntryPostingEvent} and posts journal entries asynchronously after the savings
 * transaction commits. This ensures:
 * <ul>
 * <li>Journal entries are only created after the savings transaction commits successfully</li>
 * <li>Failed journal entry posting does NOT roll back the savings transaction</li>
 * <li>Retry logic handles transient failures (3 attempts with exponential backoff)</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AsyncSavingsJournalEntryListener {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100;

    private final JournalEntryWritePlatformService journalEntryWritePlatformService;

    @Async(TaskExecutorConstant.DEFAULT_TASK_EXECUTOR_BEAN_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleJournalEntryPosting(SavingsJournalEntryPostingEvent event) {
        try {
            ThreadLocalContextUtil.init(event.getFineractContext());
            postWithRetry(event.getAccountingBridgeData());
        } catch (Exception e) {
            // Log but do NOT re-throw — a failed journal entry must never affect the savings transaction
            Long savingsId = (Long) event.getAccountingBridgeData().get("savingsId");
            log.error("Failed to post journal entries for savings account {} after {} retries", savingsId, MAX_RETRIES, e);
        } finally {
            ThreadLocalContextUtil.reset();
        }
    }

    private void postWithRetry(Map<String, Object> accountingBridgeData) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
                return; // success
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    throw e; // will be caught by the outer catch in handleJournalEntryPosting
                }
                Long savingsId = (Long) accountingBridgeData.get("savingsId");
                log.warn("Journal entry posting attempt {}/{} failed for savings account {}, retrying in {}ms: {}", attempt, MAX_RETRIES,
                        savingsId, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during journal entry retry", ie);
                }
                backoff *= 2;
            }
        }
    }
}
