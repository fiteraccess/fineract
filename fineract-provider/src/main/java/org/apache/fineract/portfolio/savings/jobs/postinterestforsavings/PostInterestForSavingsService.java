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
package org.apache.fineract.portfolio.savings.jobs.postinterestforsavings;

import static org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType.ACTIVE;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService;
import org.apache.fineract.portfolio.savings.service.SavingsDailyBalanceSyncService.SyncResult;
import org.apache.fineract.portfolio.savings.service.SavingsSchedularInterestPosterTask;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The savings interest-posting job's work, callable outside the job: the EOD chain runs it every night it closes a day.
 */
@RequiredArgsConstructor
@Slf4j
@Component
public class PostInterestForSavingsService {

    private static final int QUEUE_SIZE = 1;

    private final SavingsAccountReadPlatformService savingAccountReadPlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final ApplicationContext applicationContext;
    @Qualifier(TaskExecutorConstant.CONFIGURABLE_TASK_EXECUTOR_BEAN_NAME)
    private final ThreadPoolTaskExecutor taskExecutor;
    private final SavingsDailyBalanceSyncService savingsDailyBalanceSyncService;

    /**
     * Posts interest for every active account, {@code threadPoolSize} posters at a time over pages of
     * {@code batchSize * threadPoolSize} accounts, against the business date in the calling thread's context. Healthy
     * accounts are credited even when others fail; the failures are then thrown together.
     */
    public void postInterest(int threadPoolSize, int batchSize) throws JobExecutionException {
        // Bring the snapshot table current before we read it. Pass 1 + Pass 2 of the sync subsystem; bounded SQL.
        // See plan §6.3.
        try {
            SyncResult syncResult = savingsDailyBalanceSyncService.syncNow();
            log.info("Pre-interest savings daily balance sync: upserted={}, drained={}, from={}, to={}", syncResult.upserted(),
                    syncResult.drained(), syncResult.from(), syncResult.to());
        } catch (RuntimeException ex) {
            // Don't fail the whole interest job if the sync hits a transient DB error — the hourly job will catch up.
            log.warn("Pre-interest savings daily balance sync failed; proceeding with possibly-stale snapshots", ex);
        }

        final Queue<List<SavingsAccountData>> queue = new ArrayDeque<>();
        final List<Throwable> failures = new ArrayList<>();
        taskExecutor.setCorePoolSize(threadPoolSize);
        taskExecutor.setMaxPoolSize(threadPoolSize);
        final int pageSize = batchSize * threadPoolSize;
        Long maxSavingsIdInList = 0L;
        final boolean backdatedTxnsAllowedTill = this.configurationDomainService.retrievePivotDateConfig();

        long start = System.currentTimeMillis();

        log.debug("Reading Savings Account Data!");
        List<SavingsAccountData> savingsAccounts = savingAccountReadPlatformService
                .retrieveAllSavingsDataForInterestPosting(backdatedTxnsAllowedTill, pageSize, ACTIVE.getValue(), maxSavingsIdInList);

        if (savingsAccounts != null && savingsAccounts.size() > 0) {
            savingsAccounts = Collections.synchronizedList(savingsAccounts);
            long finish = System.currentTimeMillis();
            log.debug("Done fetching Data within {} milliseconds", finish - start);
            queue.add(savingsAccounts);

            if (!CollectionUtils.isEmpty(queue)) {
                do {
                    int totalFilteredRecords = savingsAccounts.size();
                    log.debug("Starting Interest posting - total records - {}", totalFilteredRecords);
                    List<SavingsAccountData> queueElement = queue.element();
                    maxSavingsIdInList = queueElement.get(queueElement.size() - 1).getId();
                    postInterest(queue.remove(), threadPoolSize, backdatedTxnsAllowedTill, pageSize, maxSavingsIdInList, queue, failures);
                } while (!CollectionUtils.isEmpty(queue));
            }
        }
        // Remaining pages still run so healthy accounts are credited; the run is then marked failed in its history.
        if (!failures.isEmpty()) {
            throw new JobExecutionException(failures);
        }
    }

    private void postInterest(List<SavingsAccountData> savingsAccounts, int threadPoolSize, final boolean backdatedTxnsAllowedTill,
            final int pageSize, Long maxSavingsIdInList, Queue<List<SavingsAccountData>> queue, List<Throwable> failures) {
        List<Callable<Void>> posters = new ArrayList<>();
        int fromIndex = 0;
        int size = savingsAccounts.size();
        int batchSize = (int) Math.ceil((double) size / threadPoolSize);

        if (batchSize == 0) {
            return;
        }

        int toIndex = (batchSize > size - 1) ? size : batchSize;
        while (toIndex < size && savingsAccounts.get(toIndex - 1).getId().equals(savingsAccounts.get(toIndex).getId())) {
            toIndex++;
        }
        boolean lastBatch = false;
        int loopCount = size / batchSize + 1;

        FineractContext context = ThreadLocalContextUtil.getContext();

        Callable<Void> fetchData = () -> {
            try {
                ThreadLocalContextUtil.init(context);
                Long maxId = maxSavingsIdInList;
                if (!queue.isEmpty()) {
                    maxId = Math.max(maxSavingsIdInList, queue.element().get(queue.element().size() - 1).getId());
                }

                while (queue.size() <= QUEUE_SIZE) {
                    log.debug("Fetching while threads are running!");
                    List<SavingsAccountData> savingsAccountDataList = Collections.synchronizedList(this.savingAccountReadPlatformService
                            .retrieveAllSavingsDataForInterestPosting(backdatedTxnsAllowedTill, pageSize, ACTIVE.getValue(), maxId));
                    if (savingsAccountDataList.isEmpty()) {
                        break;
                    }
                    maxId = savingsAccountDataList.get(savingsAccountDataList.size() - 1).getId();
                    queue.add(savingsAccountDataList);
                }
                return null;
            } finally {
                ThreadLocalContextUtil.reset();
            }
        };
        posters.add(fetchData);

        for (long i = 0; i < loopCount; i++) {
            List<SavingsAccountData> subList = safeSubList(savingsAccounts, fromIndex, toIndex);
            SavingsSchedularInterestPosterTask savingsSchedularInterestPosterTask = applicationContext
                    .getBean(SavingsSchedularInterestPosterTask.class);
            savingsSchedularInterestPosterTask.setSavingAccounts(subList);
            savingsSchedularInterestPosterTask.setBackdatedTxnsAllowedTill(backdatedTxnsAllowedTill);
            savingsSchedularInterestPosterTask.setContext(ThreadLocalContextUtil.getContext());

            posters.add(savingsSchedularInterestPosterTask);

            if (lastBatch) {
                break;
            }
            if (toIndex + batchSize > size - 1) {
                lastBatch = true;
            }
            fromIndex = fromIndex + (toIndex - fromIndex);
            toIndex = (toIndex + batchSize > size - 1) ? size : toIndex + batchSize;
            while (toIndex < size && savingsAccounts.get(toIndex - 1).getId().equals(savingsAccounts.get(toIndex).getId())) {
                toIndex++;
            }
        }

        List<Future<Void>> responses = new ArrayList<>();
        posters.forEach(poster -> responses.add(taskExecutor.submit(poster)));

        checkCompletion(responses, failures);
        log.debug("Queue size {}", queue.size());
    }

    private <T> List<T> safeSubList(List<T> list, int fromIndex, int toIndex) {
        int size = list.size();
        if (fromIndex >= size || toIndex <= 0 || fromIndex >= toIndex) {
            return Collections.emptyList();
        }

        fromIndex = Math.max(0, fromIndex);
        toIndex = Math.min(size, toIndex);

        return list.subList(fromIndex, toIndex);
    }

    private void checkCompletion(List<Future<Void>> responses, List<Throwable> failures) {
        for (Future<Void> f : responses) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while interest posting entries", e);
                failures.add(e);
            } catch (ExecutionException e) {
                log.error("Execution exception while interest posting entries", e.getCause());
                failures.add(e.getCause() == null ? e : e.getCause());
            }
        }
    }
}
