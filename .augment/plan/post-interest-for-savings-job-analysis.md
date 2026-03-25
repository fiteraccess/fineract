# Post Interest for Savings scheduled job analysis

## Scope
This report traces the Apache Fineract **Post Interest For Savings** scheduled job from scheduler registration through task execution, persistence, transactions, and scale characteristics.

## Key files
- `fineract-core/src/main/java/org/apache/fineract/infrastructure/jobs/service/JobName.java`
- `fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/jobs/postinterestforsavings/PostInterestForSavingConfig.java`
- `fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/jobs/postinterestforsavings/PostInterestForSavingTasklet.java`
- `fineract-provider/src/main/java/org/apache/fineract/infrastructure/jobs/service/JobRegisterServiceImpl.java`
- `fineract-provider/src/main/java/org/apache/fineract/infrastructure/jobs/service/JobStarter.java`
- `fineract-provider/src/main/java/org/apache/fineract/infrastructure/jobs/service/SchedulerJobListener.java`
- `fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountReadPlatformServiceImpl.java`
- `fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountWritePlatformServiceJpaRepositoryImpl.java`
- `fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountInterestPostingServiceImpl.java`
- `fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsSchedularInterestPoster.java`
- `fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsSchedularInterestPosterTask.java`
- `fineract-provider/src/main/resources/db/changelog/tenant/parts/0002_initial_data.xml`
- `fineract-provider/src/main/resources/db/changelog/tenant/upgrades/0000_upgrade_to_1.6.xml`

## 1. Entry point
### Job definition
- The job enum is `JobName.POST_INTEREST_FOR_SAVINGS` with display name **Post Interest For Savings**.
- Spring Batch wiring lives in `PostInterestForSavingConfig`:
  - `postInterestForSavingStep(PostInterestForSavingTasklet)` creates a tasklet step named `POST_INTEREST_FOR_SAVINGS`.
  - `postInterestForSavingJob(PostInterestForSavingTasklet)` creates the job and adds `RunIdIncrementer`.

### Scheduler trigger path
- Quartz scheduling is handled in `JobRegisterServiceImpl`.
- `createJobDetail(...)` resolves the Spring Batch job via `jobLocator.getJob(jobName.getEnumStyleName())`.
- Quartz uses `MethodInvokingJobDetailFactoryBean` to invoke `JobStarter.run(...)`.
- `createTrigger(...)` builds a cron trigger from `ScheduledJobDetail.cronExpression`.
- The seeded job row is in `0002_initial_data.xml` with `job.id = 6`, cron `0 0 0 1/1 * ? *`.

### Job parameters
- `JobStarter.getJobParameter(...)` loads parameters from the `job_parameters` table through `JobParameterRepository.findJobParametersByJobId(...)`.
- Seed data for job `6` sets:
  - `thread-pool-size = 10`
  - `batch-size = 100`
- These defaults appear in both `0002_initial_data.xml` and upgrade script `0000_upgrade_to_1.6.xml`.

## 2. Execution flow
1. Quartz fires the job and invokes `JobStarter.run(...)`.
2. `JobStarter` initializes tenant/security/business-date context, builds Spring Batch `JobParameters`, and launches the batch job.
3. `PostInterestForSavingTasklet.execute(...)` reads `thread-pool-size` and `batch-size`, then computes `pageSize = batchSize * threadPoolSize`.
4. It reads the first page of eligible accounts via `SavingsAccountReadPlatformService.retrieveAllSavingsDataForInterestPosting(backdatedTxnsAllowedTill, pageSize, ACTIVE.getValue(), maxSavingsId)`.
5. The tasklet keeps a queue of prefetched pages and overlaps the next read with current processing.
6. For each page, `postInterest(...)` splits work into sublists sized for the thread pool, but extends boundaries so rows for the same savings account ID stay in the same sublist.
7. Each sublist is processed by a new `SavingsSchedularInterestPosterTask`, which restores `FineractContext` and calls `SavingsSchedularInterestPoster.postInterest()`.
8. `SavingsSchedularInterestPoster.postInterest()` loops accounts in its assigned batch and calls `SavingsAccountWritePlatformServiceJpaRepositoryImpl.postInterest(...)` per account.
9. The per-account service delegates to `SavingsAccountInterestPostingServiceImpl.postInterest(...)`, which:
   - computes posting periods,
   - creates or reverses interest-posting / overdraft-interest transactions,
   - optionally creates withholding-tax transactions,
   - recomputes summary balances and interest dates in memory.
10. If all accounts in the worker succeed, `SavingsSchedularInterestPoster.batchUpdate(...)` persists summary changes, inserts new savings transactions, updates reversed/corrected transactions, rereads inserted transaction IDs by `ref_no`, and inserts GL journal entries.
11. The tasklet waits on all futures, logs completion, and returns `RepeatStatus.FINISHED`.

## 3. Exit points
### Normal completion
- `PostInterestForSavingTasklet.execute(...)` returns `RepeatStatus.FINISHED`.
- `JobStarter.run(...)` treats the batch run as successful unless Spring Batch returns a failed status.
- `SchedulerJobListener.jobWasExecuted(...)` then marks the run `SUCCESS`, updates the job row, and writes a `job_run_history` row.

### Failure paths
- `SavingsSchedularInterestPoster.postInterest()` collects worker errors and throws `org.apache.fineract.infrastructure.jobs.exception.JobExecutionException` if any account processing or batch persistence fails.
- `JobStarter.run(...)` throws Quartz `JobExecutionException` if the Spring Batch `JobExecution` ends in `FAILED`, `ABANDONED`, `STOPPED`, `STOPPING`, or `UNKNOWN`.
- `SchedulerJobListener.jobWasExecuted(...)` marks the run `FAILED` when Quartz surfaces an exception, and stores `errorMessage` / `errorLog`.

### Important caveat
- `PostInterestForSavingTasklet.checkCompletion(...)` catches `InterruptedException` and `ExecutionException` from `Future.get()` and only logs them.
- Because it does not rethrow, worker-thread failures may be logged without necessarily failing the Spring Batch step, which creates a realistic risk of **false-success reporting** at the scheduler level.

## 4. Data persistence
### Primary reads
`SavingsAccountReadPlatformServiceImpl.retrieveAllSavingsDataForInterestPosting(...)` reads from:
- `m_savings_account`
- `m_savings_account_transaction`
- `m_savings_product`
- `m_currency`
- `m_client`
- `m_group`
- `m_payment_detail`, `m_payment_type`
- `m_savings_account_charge_paid_by`, `m_savings_account_charge`
- `m_tax_group`, `m_savings_account_transaction_tax_details`, `m_tax_component`
- `acc_product_mapping`

It pages with `sa.id > ?`, filters by active status and due interest-posting date, and orders by account/transaction chronology.

### Primary writes
`SavingsSchedularInterestPoster.batchUpdate(...)` writes to:
- `m_savings_account`
  - updates derived summary columns such as `total_deposits_derived`, `total_withdrawals_derived`, `total_interest_earned_derived`, `total_interest_posted_derived`, `total_withdrawal_fees_derived`, `total_fees_charge_derived`, `total_penalty_charge_derived`, `total_annual_fees_derived`, `account_balance_derived`, `total_overdraft_interest_derived`, `total_withhold_tax_derived`, `last_interest_calculation_date`, `interest_posted_till_date`, audit columns.
- `m_savings_account_transaction`
  - inserts new interest / overdraft-interest / withholding-tax rows with fields including `savings_account_id`, `office_id`, `transaction_type_enum`, `transaction_date`, `amount`, `balance_*_derived`, `running_balance_derived`, `cumulative_balance_derived`, `is_manual`, `ref_no`, `is_reversal`, `overdraft_amount_derived`, `submitted_on_date`, audit columns.
  - updates existing rows for reversals/corrections (`is_reversed`, `amount`, `overdraft_amount_derived`, balance-derived fields, `is_reversal`, audit columns).
- `acc_gl_journal_entry`
  - inserts double-entry accounting lines for the newly persisted savings transactions.

### Scheduler metadata writes
- `SchedulerJobListener` updates the `job` table through `ScheduledJobDetail` (`previous_run_start_time`, `currently_running`, `next_run_time`, status-related fields).
- It also persists `ScheduledJobRunHistory` into `job_run_history`.

## 5. Transaction scope
- The batch step itself is created with the batch `PlatformTransactionManager`, but the actual savings posting work runs in separate worker threads.
- The effective business-data transaction boundary is therefore **per worker batch/sublist**, not one transaction for the whole scheduled job.
- `SavingsSchedularInterestPoster.postInterest()` is explicitly annotated `@Transactional(isolation = Isolation.READ_UNCOMMITTED, rollbackFor = Exception.class)`.
- Inside that transaction, calls to `SavingsAccountWritePlatformServiceJpaRepositoryImpl.postInterest(...)` (also `@Transactional`) typically join the existing worker transaction under default propagation.
- If any exception escapes the worker, the worker transaction rolls back. If all per-account calls succeed, the later JDBC batch updates and GL inserts commit together with that worker transaction.
- Because workers run independently, one worker batch can commit while another fails.

## 6. Scalability and performance
### What helps it scale
- Pagination by max savings account ID.
- Parallel processing using `ThreadPoolTaskExecutor`.
- Page size = `thread-pool-size * batch-size`.
- Prefetch queue overlaps DB reads with current page processing.
- Batched JDBC writes reduce round trips for summary updates, transaction inserts/updates, and GL entries.

### Bottlenecks / risks
- The read query is wide and join-heavy; `m_savings_account_transaction` growth can make it expensive.
- The worker transaction uses `READ_UNCOMMITTED`, which improves concurrency but weakens isolation guarantees.
- Each worker keeps a page/sublist in memory and mutates full `SavingsAccountData` graphs before flushing.
- The design is parallel on a single node only; it is not remote-partitioned or cluster-distributed like Fineract COB jobs.
- `checkCompletion(...)` swallowing future failures is the main correctness/operability risk because scheduler status may not reflect worker failure.

## Bottom line
The job is a Quartz-triggered Spring Batch tasklet job that pages active savings accounts due for interest posting, parallelizes processing across worker threads, computes interest/withholding adjustments in memory, and persists results in JDBC batches. Its strongest scale features are paging, batching, and local parallelism; its main operational concern is that worker-thread exceptions can be logged but not propagated by the tasklet, potentially masking failures.
