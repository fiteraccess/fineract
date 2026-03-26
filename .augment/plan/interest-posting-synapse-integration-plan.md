# Interest Posting — Synapse Integration Plan

## Architecture Summary

- **Synapse** = single entry point for ALL savings debits/credits (real-time transaction processor, backed by TigerBeetle)
- **Fineract** = system of record; keeps interest calculation logic, backdated recalculation, product rules
- **Flow**: Fineract calculates interest → sends posting instructions to Synapse → Synapse processes → Fineract catches up from queue

---

## 1. What Changes

### Primary Target

**File:** `fineract-savings/.../SavingsSchedularInterestPoster.java`
**Method:** `batchUpdate(...)`

This method currently:
1. Loops over `savingsAccountTransactionDataList`
2. Splits transactions into INSERT vs UPDATE arrays
3. Batch-inserts/updates transaction rows in `m_savings_account_transaction`
4. Updates `m_savings_account` summary fields (balance, interest posted, dates)
5. Fetches back generated IDs
6. Creates journal entries

**What changes:** Steps 2–6 are replaced with:
- Collect transactions into a typed list
- Send batch request to Synapse
- Mark interest as pending/outbound locally

### Secondary Target (later phase)

**File:** `fineract-provider/.../SavingsAccountWritePlatformServiceJpaRepositoryImpl.java`
**Method:** `postInterest(...)` (manual/non-scheduler path, lines ~554–597)

This path uses `savingsAccountTransactionRepository.save(...)` directly. Same pattern applies but is a separate phase.

### What Does NOT Change

- `SavingsAccountWritePlatformServiceJpaRepositoryImpl.postInterest()` **calculation logic** (lines ~603–639)
- Interest amount computation
- Product configuration lookups
- Overdraft/tax/reversal determination
- `selectAccountId()` GL account resolution

---

## 2. Collection Data Structure

### DTO: `SynapseTransactionInstruction`

```java
public class SynapseTransactionInstruction {

    private String traceId;           // UUID, idempotency key
    private Long savingsAccountId;
    private Long officeId;
    private String externalId;        // savings account external ID if available

    // Transaction semantics
    private TransactionType transactionType;  // INTEREST_POSTING, OVERDRAFT_INTEREST, WITHHOLD_TAX
    private Direction direction;              // CREDIT or DEBIT
    private Operation operation;              // POST or REVERSE

    // Monetary
    private BigDecimal amount;
    private BigDecimal overdraftAmount;
    private LocalDate transactionDate;
    private String currencyCode;

    // Correlation
    private String refNo;                     // from SavingsAccountTransactionData.refNo
    private Long originalTransactionId;       // if reversal
    private String batchId;                   // scheduler run identifier

    public enum TransactionType {
        INTEREST_POSTING, OVERDRAFT_INTEREST, WITHHOLD_TAX
    }

    public enum Direction {
        CREDIT, DEBIT
    }

    public enum Operation {
        POST, REVERSE
    }
}
```

### Batch wrapper: `SynapseInterestPostingBatch`

```java
public class SynapseInterestPostingBatch {
    private String batchId;          // unique per scheduler run
    private LocalDate postingDate;
    private int totalCount;
    private List<SynapseTransactionInstruction> transactions;
}
```

---

## 3. Collection Point

### Where to collect (in `batchUpdate`)

The collection boundary is the **per-transaction loop**, covering both branches:

```java
List<SynapseTransactionInstruction> collectedTransactions = new ArrayList<>();

for (SavingsAccountTransactionData tx : savingsAccountTransactionDataList) {
    if (tx.getId() == null && !MathUtil.isZero(tx.getAmount())) {
        // NEW posting → collect as Operation.POST
        collectedTransactions.add(mapToInstruction(tx, Operation.POST, batchId));
    } else if (tx.getId() != null) {
        // EXISTING transaction being modified → collect as Operation.REVERSE if reversed
        if (tx.isReversed()) {
            collectedTransactions.add(mapToInstruction(tx, Operation.REVERSE, batchId));
        }
        // Pure derived-balance updates (not reversed, just recalculated) → do NOT send to Synapse
    }
}
```

### What to skip

- Transactions with zero amount → skip (already filtered)
- Pure derived-balance recalculations (UPDATE branch, not reversed) → skip
- Summary field updates to `m_savings_account` → handle separately (see §5)

---

## 4. Synapse API Call

### Option A: Reuse existing proxy endpoints (quick start)

For each collected transaction, call:

**Credit (INTEREST_POSTING):**
```
POST {synapse-base}/fineract-provider/api/v1/savingsaccounts/{savingsAccountId}/transactions?command=deposit
```

**Debit (OVERDRAFT_INTEREST, WITHHOLD_TAX):**
```
POST {synapse-base}/fineract-provider/api/v1/savingsaccounts/{savingsAccountId}/transactions?command=withdrawal
```

**Response:**
```json
{
  "batchId": "run-2026-03-12T02:00:00Z",
  "accepted": 2,
  "failed": 0,
  "results": [
    { "traceId": "550e8400-...-440000", "status": "ACCEPTED", "correlationId": "syn-tx-98765" },
    { "traceId": "550e8400-...-440001", "status": "ACCEPTED", "correlationId": "syn-tx-98766" }
  ]
}
```

**This endpoint needs to be built in the Synapse proxy.**

---

## 5. Duplicate Prevention / Posting Cursor

### The Problem

`batchUpdate()` currently updates `m_savings_account` summary fields:
- `total_interest_posted_derived`
- `account_balance_derived`
- `last_interest_calculation_date`
- `interest_posted_till_date`

If we stop persisting transactions locally but also stop updating these fields, the next scheduler run will recalculate and re-post the same interest.

### Solution: Advance the posting cursor without persisting the transaction

After successfully sending to Synapse, **still update the summary fields locally**:

```sql
UPDATE m_savings_account SET
    interest_posted_till_date = ?,
    last_interest_calculation_date = ?
WHERE id = ?
```

But do **NOT** insert the transaction row or update balance fields.

This way:
- The next scheduler run knows interest was already posted through that date
- The actual transaction row and balance update arrive later via the queue replay
- No duplicate interest calculation occurs

### What to keep in `batchUpdate()`
- ✅ `paramsForSavingsSummary` → keep, but only update date cursor fields
- ❌ `paramsForTransactionInsertion` → remove (replaced by Synapse call)
- ❌ `paramsForTransactionUpdate` → remove for reversed transactions (replaced by Synapse call)
- ❌ `fetchTransactionsFromIds()` → remove
- ❌ `batchUpdateJournalEntries()` → remove (Synapse/queue replay handles this)

### Idempotency

- Each `SynapseTransactionInstruction` carries a `traceId` (UUID)
- Synapse must reject or deduplicate duplicate `traceId`s
- If the Synapse call fails, Fineract does NOT advance the posting cursor
- Next scheduler run will recalculate and retry

---

## 6. Error Handling

### Synapse call succeeds
1. Advance posting cursor (`interest_posted_till_date`)
2. Log batch confirmation
3. Continue to next account batch

### Synapse call fails (network/timeout)
1. Do NOT advance posting cursor
2. Log error with batch ID and affected account IDs
3. Next scheduler run will recalculate and retry (idempotent via `traceId`)

### Synapse call partially succeeds (batch endpoint)
1. Only advance posting cursor for accounts that succeeded
2. Failed accounts will be retried on next run
3. Synapse deduplicates already-accepted `traceId`s

### Synapse rejects a transaction
1. Log rejection reason
2. Do NOT advance posting cursor for that account
3. Alert/escalate if rejection is due to business rules (insufficient balance for tax, etc.)

---

## 7. Queue Replay (Fineract catching up)

When Synapse processes the interest posting in TigerBeetle, it calls back into Fineract
so Fineract can record the transaction in its own ledger.

### Endpoint: follows existing Fineract command pattern

The replay endpoint follows the **exact same pattern** as deposit and withdrawal:

```
API Resource → CommandWrapperBuilder → CommandHandler → Service
```

**Existing deposit pattern for reference:**

| Layer | Deposit | Withdrawal |
|---|---|---|
| URL | `POST /v1/savingsaccounts/{id}/transactions?command=deposit` | `?command=withdrawal` |
| CommandWrapperBuilder | `savingsAccountDeposit(id)` → `action=DEPOSIT, entity=SAVINGSACCOUNT` | `savingsAccountWithdrawal(id)` |
| Handler | `@CommandType(entity="SAVINGSACCOUNT", action="DEPOSIT")` | `action="WITHDRAWAL"` |
| Service | `writePlatformService.deposit(savingsId, command)` | `.withdrawal(savingsId, command)` |

**New replay endpoint — same pattern:**

| Layer | Interest Posting Replay |
|---|---|
| URL | `POST /v1/savingsaccounts/{savingsId}/transactions?command=replayInterestPosting` |
| CommandWrapperBuilder | `savingsAccountReplayInterestPosting(savingsId)` → `action=REPLAYINTERESTPOSTING, entity=SAVINGSACCOUNT` |
| Handler | `@CommandType(entity="SAVINGSACCOUNT", action="REPLAYINTERESTPOSTING")` |
| Service | `writePlatformService.replayInterestPosting(savingsId, command)` |

### Request body

Follows Fineract's standard JSON command format (`JsonCommand`):

```json
{
  "transactionDate": "20 March 2026",
  "transactionAmount": 250.00,
  "dateFormat": "dd MMMM yyyy",
  "locale": "en",
  "transactionType": "INTEREST_POSTING",
  "overdraftAmount": null,
  "traceId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "correlationId": "syn-tx-98765"
}
```

### What the service method does

1. Loads the `SavingsAccount` entity
2. Deduplicates on `traceId` — if a transaction with this traceId already exists, returns
   200 with the existing transaction ID (no double-posting)
3. Creates a `SavingsAccountTransaction` with the correct `SavingsAccountTransactionType`:
   - `INTEREST_POSTING` (3) — credits the account
   - `OVERDRAFT_INTEREST` (17) — debits the account
   - `WITHHOLD_TAX` (18) — debits the account
4. Persists via `savingsAccountTransactionRepository.save()`
5. Updates `account_balance_derived`, `total_interest_posted_derived` on `m_savings_account`
6. Creates journal entries with the correct GL accounts (interest expense → savings)
7. Updates running balances

### What it does NOT do

- Recalculate interest (already calculated by the scheduler)
- Check business rules (min balance, holds, overdraft limits)
- Advance the posting cursor (already advanced when the batch was dispatched)

### Files to create/modify

| File | Change |
|---|---|
| `SavingsAccountTransactionsApiResource` | Add `replayInterestPosting` branch in `transaction()` method |
| `CommandWrapperBuilder` | Add `savingsAccountReplayInterestPosting(Long accountId)` |
| **New:** `ReplayInterestPostingSavingsAccountCommandHandler` | `@CommandType(entity="SAVINGSACCOUNT", action="REPLAYINTERESTPOSTING")` |
| `SavingsAccountWritePlatformService` | Add `replayInterestPosting(Long savingsId, JsonCommand command)` |
| `SavingsAccountWritePlatformServiceJpaRepositoryImpl` | Implement `replayInterestPosting()` |

This endpoint is **not yet built** — it is a prerequisite for end-to-end integration testing.

---

## 8. Local Persistence Layer Decision

See **[synapse-persistence-layer-decision.md](synapse-persistence-layer-decision.md)** for the
full evaluation of whether to introduce `m_synapse_posting_instruction`.

**TL;DR:** Continue with in-memory DTOs for Phase 1 and Phase 2. Introduce the table as the
first step of Phase 3 (Reversals), when original-instruction lookups become necessary and the
double-posting risk compounds with reversal logic.

---

## 9. Implementation Phases

### Phase 1: Collection + API call (scheduler path) ✅
1. Create `SynapseTransactionInstruction` and `SynapseInterestPostingBatch` DTOs
2. Create `SynapseTransactionClient` service (HTTP client for Synapse)
3. Modify `SavingsSchedularInterestPoster.batchUpdate()`:
   - Collect transactions instead of building SQL param arrays
   - Call Synapse batch endpoint
   - Update only posting cursor fields locally
4. Build batch interest-posting endpoint in Synapse proxy ← **Synapse team**
5. Test with a small subset of accounts

### Phase 1b: Replay endpoint (Fineract — prerequisite for end-to-end)
1. Build `POST /api/v1/internal/savings/interest-postings:replay` endpoint
2. Accepts `traceId`, `savingsAccountId`, `transactionType`, `direction`, `amount`,
   `transactionDate`, `currencyCode`, `correlationId`, `overdraftAmount`
3. Deduplicates on `traceId`
4. Inserts correct `SavingsAccountTransactionType` row (not deposit/withdrawal)
5. Updates balance derived fields, creates journal entries, updates running balances
6. Skips business rules (min balance, holds) and maker-checker pipeline
7. Does NOT recalculate interest or advance posting cursor

### Phase 2: Manual/non-scheduler path
1. Modify `SavingsAccountWritePlatformServiceJpaRepositoryImpl.postInterest()` (manual path)
2. Same pattern: collect → send to Synapse → update cursor
3. Can use individual endpoint (Option A) since manual postings are one-at-a-time

### Phase 3: Reversals and backdated recalculation
1. Handle reversal flows through Synapse
2. Backdated transaction triggers recalculation in Fineract
3. Fineract sends adjustment/compensating postings to Synapse

### Phase 4: Other derived transactions
1. Monthly charges
2. Dormancy fees
3. Any other Fineract-originated debits/credits

---

## 9. Files to Modify

| File | Change |
|------|--------|
| `fineract-savings/.../SavingsSchedularInterestPoster.java` | Replace direct DB persistence with collection + Synapse API call |
| `fineract-provider/.../SavingsAccountWritePlatformServiceJpaRepositoryImpl.java` | Phase 2: same pattern for manual path |
| **New:** `fineract-savings/.../SynapseTransactionInstruction.java` | Collection DTO |
| **New:** `fineract-savings/.../SynapseInterestPostingBatch.java` | Batch wrapper DTO |
| **New:** `fineract-provider/.../SynapseTransactionClient.java` | HTTP client service for Synapse |
| **New (Synapse proxy):** batch interest-posting endpoint + handler | Accepts batch, fans out to TigerBeetle |

---

## 10. Configuration

New Fineract configuration properties:

```yaml
fineract:
  synapse:
    enabled: true
    base-url: http://synapse-proxy:8080/fineract-provider
    batch-endpoint: /api/v1/proxy/savings/interest-postings:batch
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
    retry-max-attempts: 3
    retry-backoff-ms: 1000
```

Feature flag: `synapse.enabled` allows gradual rollout and fallback to direct persistence.

---

## 11. Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Double interest posting | Posting cursor advancement + Synapse idempotency via `traceId` |
| Synapse downtime during scheduler run | Retry with backoff; cursor not advanced on failure |
| Queue replay lag causes stale balances in Fineract | Acceptable — Fineract is not the real-time source; Synapse/TigerBeetle is |
| Partial batch failure | Per-account cursor tracking; failed accounts retry next run |
| Feature flag rollback | If `synapse.enabled=false`, fall back to original direct persistence path |

