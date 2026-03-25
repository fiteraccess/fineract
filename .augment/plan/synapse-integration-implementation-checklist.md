# Synapse Integration — Implementation Checklist (Phase 1: Scheduler Path)

## Overview

Replace direct DB persistence in the interest-posting scheduler with: **calculate → collect → send to Synapse → advance cursor**.
Feature-flagged behind `fineract.synapse.enabled` (default `false`); when off, the original path runs unchanged.

---

## Module 1: DTOs ✅

**Package:** `fineract-savings/.../savings/data/synapse/`

- [x] `SynapseTransactionInstruction` — single posting instruction (traceId, account, direction, operation, amount, etc.)
- [x] `SynapseInterestPostingBatch` — batch wrapper (batchId, postingDate, totalCount, list of instructions)

---

## Module 2: Configuration ✅

**Goal:** Add `fineract.synapse.*` properties following the existing `FineractProperties` nested-class pattern.

### Files to create
- [x] `FineractProperties.FineractSynapseProperties` — nested static class inside `FineractProperties`
  - `boolean enabled` (default `false`)
  - `String baseUrl`
  - `String batchEndpoint` (default `/api/v1/proxy/savings/interest-postings:batch`)
  - `long connectTimeoutMs` (default `5000`)
  - `long readTimeoutMs` (default `30000`)
  - `int retryMaxAttempts` (default `3`)
  - `long retryBackoffMs` (default `1000`)

### Files to modify
- [x] `FineractProperties` — add `private FineractSynapseProperties synapse;` field
- [x] `application.properties` — add defaults with `FINERACT_SYNAPSE_*` env var overrides

---

## Module 3: Synapse HTTP Client ✅

**Package:** `fineract-savings/.../savings/service/synapse/` and `data/synapse/`

### Files created
- [x] `SynapsePostingResult` — response DTO (traceId, status, correlationId) — in `data/synapse/`
- [x] `SynapseBatchPostingResponse` — response wrapper (batchId, accepted, failed, results list) — in `data/synapse/`
- [x] `SynapsePostingException` — unchecked exception (from Module 7, needed here) — in `service/synapse/`
- [x] `SynapseTransactionClient` — concrete class using `RestTemplate` (no interface)
  - Reads `FineractSynapseProperties` for URL, timeouts
  - Sends POST to `{baseUrl}{batchEndpoint}`
  - Handles HTTP errors, timeouts → throws `SynapsePostingException`
  - Logs request/response at DEBUG level

### Files modified
- [x] `SavingsConfiguration` — register `SynapseTransactionClient` bean (`@ConditionalOnProperty` on `synapse.enabled`)

---

## Module 4: Mapping Logic ✅

**Package:** `fineract-savings/.../savings/service/synapse/`

### Files created
- [x] `SynapseInstructionMapper` — stateless mapper (concrete class, no interface)
  - `SynapseTransactionInstruction map(SavingsAccountData account, SavingsAccountTransactionData tx, Operation op, String batchId)`
  - Determines `TransactionType` from `SavingsAccountTransactionEnumData` (interest posting → INTEREST_POSTING, overdraft interest → OVERDRAFT_INTEREST, withhold tax → WITHHOLD_TAX)
  - Determines `Direction` from transaction type (interest posting = CREDIT, overdraft/tax = DEBIT)
  - Sets `traceId` = UUID, `refNo` = tx.refNo, `currencyCode` from account currency
  - Bean registered in `SavingsConfiguration` conditional on `synapse.enabled`

---

## Module 5: `SynapseInterestPostingService` (extracted orchestration)

**Package:** `fineract-savings/.../savings/service/synapse/`

**Why extract?** `SavingsSchedularInterestPoster.batchUpdate()` is tightly coupled to `JdbcTemplate`,
`PlatformSecurityContext`, etc. Testing it directly requires Spring context + database. By extracting the
Synapse-specific orchestration into its own class, we get a clean unit-test boundary with zero intrusion
into the existing code.

### Files to create
- [ ] `SynapseInterestPostingService` — owns the full Synapse posting flow:
  - Constructor deps: `SynapseInstructionMapper`, `SynapseTransactionClient`
  - `SynapsePostResult postInterestBatch(List<SavingsAccountData> accounts, LocalDate postingDate)`
    1. Generates a `batchId` (UUID)
    2. Iterates accounts → transactions, delegates to mapper for each eligible tx
    3. Wraps collected instructions in `SynapseInterestPostingBatch`
    4. Calls `synapseClient.postBatch(batch)`
    5. Returns a `SynapsePostResult` containing the cursor-update params per account
       (account ID, interestPostedTillDate, lastInterestCalculationDate)
  - Does **not** touch `JdbcTemplate` — returns data, caller persists

- [ ] `SynapsePostResult` — simple data holder
  - `List<AccountCursorUpdate> cursorUpdates` (accountId, interestPostedTillDate, lastInterestCalculationDate)
  - `int accepted`, `int failed`

### What this enables
- The service is testable with: real mapper + mock client → assert batch payload + cursor params
- No JdbcTemplate, no PlatformSecurityContext, no Spring context needed in tests

---

## Module 6: Modify `batchUpdate()`

**File:** `SavingsSchedularInterestPoster.java`

### What changes
- [ ] Add constructor dependency: `SynapseInterestPostingService` (nullable — only present when enabled)
- [ ] Add constructor dependency: `FineractProperties` (to check `synapse.enabled`)
- [ ] Inside `batchUpdate()`, thin branch on feature flag:

```java
if (synapseEnabled) {
    SynapsePostResult result = synapseInterestPostingService.postInterestBatch(savingsAccountDataList, currentDate);
    executeCursorUpdates(result.getCursorUpdates());
} else {
    // existing path unchanged — transaction inserts, updates, journal entries
}
```

### Cursor-only SQL (new private method)
- [ ] `batchQueryForPostingCursorUpdate()` — returns:
  ```sql
  UPDATE m_savings_account
  SET interest_posted_till_date = ?,
      last_interest_calculation_date = ?,
      last_modified_on_utc = ?,
      last_modified_by = ?
  WHERE id = ?
  ```
- [ ] `executeCursorUpdates(List<AccountCursorUpdate>)` — builds param array, calls `jdbcTemplate.batchUpdate`

### What to keep in the Synapse path
- ✅ Cursor date fields only (via `executeCursorUpdates`)
- ❌ `paramsForTransactionInsertion` — skip
- ❌ `paramsForTransactionUpdate` — skip
- ❌ `fetchTransactionsFromIds()` — skip
- ❌ `batchUpdateJournalEntries()` — skip

### Files to modify
- [ ] `SavingsSchedularInterestPoster` — add branch + cursor method
- [ ] `SavingsConfiguration` — update bean wiring to pass new dependencies

---

## Module 7: Error Handling

Handled inline in Modules 5–6, but worth calling out:

- [ ] Define `SynapsePostingException` (unchecked) in `service/synapse/`
- [ ] `SynapseTransactionClientImpl`: HTTP failure / timeout → wrap in `SynapsePostingException`
- [ ] `SynapseInterestPostingService`: lets `SynapsePostingException` propagate (no cursor data returned)
- [ ] `batchUpdate()`: exception propagates up → `postInterest()` catches it per-account → no cursor advance → next scheduler run retries
- [ ] Partial success: if batch endpoint returns per-instruction results, `SynapseInterestPostingService` only includes succeeded accounts in `SynapsePostResult.cursorUpdates`

---

## Module 8: Tests

### Testing strategy

The extraction in Module 5 gives us clean test boundaries. Every new class is testable in isolation
without Spring context, database, or HTTP. The existing `SavingsSchedularInterestPoster` is **not**
directly unit-tested — its Synapse branch is a 3-line delegation, and the original branch is unchanged.

### Test classes to create

**Package:** `fineract-savings/src/test/.../savings/service/synapse/`

- [ ] `SynapseInstructionMapperTest` — pure unit test, real domain objects, no mocks
  - Interest posting tx → CREDIT + INTEREST_POSTING + POST
  - Overdraft interest tx → DEBIT + OVERDRAFT_INTEREST + POST
  - Withhold tax tx → DEBIT + WITHHOLD_TAX + POST
  - Reversed tx → operation = REVERSE
  - Zero-amount tx → returns null / excluded
  - traceId is a valid UUID
  - currencyCode, refNo, savingsAccountId, officeId populated from inputs

- [ ] `SynapseInterestPostingServiceTest` — real mapper, mock client
  - Happy path: 3 accounts with mixed tx types → client receives correct batch payload → returns cursor updates for all 3
  - Accounts with only zero-amount txs → client not called (empty batch)
  - Client throws `SynapsePostingException` → exception propagates, no cursor data returned
  - Partial success response → cursor updates only for accepted accounts
  - batchId is consistent across all instructions in a single call

- [ ] `SynapseTransactionClientImplTest` — `MockRestServiceServer`
  - Successful POST → deserializes response correctly
  - HTTP 500 → throws `SynapsePostingException`
  - Connection timeout → throws `SynapsePostingException`
  - Request body serialization matches expected JSON structure

### What is NOT tested (and why)
- `SavingsSchedularInterestPoster.batchUpdate()` — the Synapse branch is a thin delegation (3 lines);
  the original branch is existing code. Testing either requires JdbcTemplate + PlatformSecurityContext
  setup with no meaningful coverage gain.
- `SavingsConfiguration` bean wiring — covered by Spring Boot integration tests if they exist;
  not worth a dedicated unit test.

---

## Execution Order

```
Module 2 (Config)
Module 3 (Client + response DTOs)
Module 4 (Mapper)
  ↓
Module 5 (SynapseInterestPostingService)  ← depends on 3 + 4
  ↓
Module 6 (Modify batchUpdate)             ← depends on 2 + 5
  ↓
Module 7 (Error handling)                 ← woven into 3, 5, 6
  ↓
Module 8 (Tests)                          ← can be written alongside each module
```

Modules 2, 3, 4 are independent of each other and can be done in any order.
All three must be done before Module 5.

