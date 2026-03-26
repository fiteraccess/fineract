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
- [x] `SynapseInterestPostingService` — owns the full Synapse posting flow:
  - Constructor deps: `SynapseInstructionMapper`, `SynapseTransactionClient`
  - `SynapsePostResult postInterestBatch(List<SavingsAccountData> accounts, LocalDate postingDate)`
    1. Generates a `batchId` (UUID)
    2. Extracts cursor data (`interestPostedTillDate`, `lastInterestCalculationDate`) from **every** account's summary — all accounts get cursor updates by default
    3. Iterates accounts → transactions, delegates to mapper for each eligible tx (new non-zero + reversed)
    4. Builds `Map<Long, List<String>>` (accountId → traceIds) during instruction collection for partial-success correlation
    5. If no instructions collected → returns result immediately (no HTTP call, all cursors included)
    6. If instructions exist → wraps in `SynapseInterestPostingBatch`, calls `synapseClient.postBatch(batch)`
    7. On response: collects failed traceIds → derives failed accountIds (if **any** instruction for an account failed, that account is failed) → removes those accounts' cursor updates
    8. Returns `SynapsePostResult` with surviving cursor updates + accepted/failed counts
  - Does **not** touch `JdbcTemplate` — returns data, caller persists

- [x] `SynapsePostResult` — simple data holder
  - `List<AccountCursorUpdate> cursorUpdates` (accountId, interestPostedTillDate, lastInterestCalculationDate)
  - `int accepted`, `int failed`

### Key design decisions
- **Zero-interest accounts** still get cursor updates — prevents re-calculation on next scheduler run
- **Partial success at account level**: Synapse returns per-instruction results. If **any** instruction for an account fails, that account's cursor is not advanced. One bad account does not block the other 999,999.
- **Empty batch** (all accounts have zero interest) skips the HTTP call entirely

### What this enables
- The service is testable with: real mapper + mock client → assert batch payload + cursor params
- No JdbcTemplate, no PlatformSecurityContext, no Spring context needed in tests

---

## Module 6: Modify `batchUpdate()` ✅

**File:** `SavingsSchedularInterestPoster.java`

### What changes
- [x] Add setter-injected dependency: `SynapseInterestPostingService` (nullable — only present when enabled)
- [x] Add setter-injected dependency: `FineractProperties` (to check `synapse.enabled`)
- [x] Inside `batchUpdate()`, early-return branch on feature flag (existing code untouched in else path)

### Cursor-only SQL (new private method)
- [x] `batchQueryForPostingCursorUpdate()` — cursor-only UPDATE
- [x] `executeCursorUpdates(List<AccountCursorUpdate>, Long userId)` — builds param array, calls `jdbcTemplate.batchUpdate`
- [x] `isSynapseEnabled()` — null-safe check on properties + service

### What to keep in the Synapse path
- ✅ Cursor date fields only (via `executeCursorUpdates`)
- ❌ `paramsForTransactionInsertion` — skip
- ❌ `paramsForTransactionUpdate` — skip
- ❌ `fetchTransactionsFromIds()` — skip
- ❌ `batchUpdateJournalEntries()` — skip

### Files modified
- [x] `SavingsSchedularInterestPoster` — add branch + cursor method
- [x] `SavingsConfiguration` — update bean wiring via `ObjectProvider` for optional deps

---

## Module 7: Error Handling ✅

Handled inline in Modules 3, 5, 6. Status:

- [x] Define `SynapsePostingException` (unchecked) in `service/synapse/` — **done in Module 3**
- [x] `SynapseTransactionClient`: HTTP failure / timeout → wrap in `SynapsePostingException` — **done in Module 3**
- [x] `SynapseInterestPostingService`: HTTP-level failure (non-200) → lets `SynapsePostingException` propagate (no cursor data returned, entire batch retried) — **already implemented: no try/catch around `client.postBatch()`**
- [x] `SynapseInterestPostingService`: 200 with per-instruction failures → removes failed accounts' cursors, returns partial result — **already implemented in `filterByResponse()`**
- [x] `batchUpdate()`: exception propagates up → `postInterest()` catches it via existing `catch (Exception)` → no cursor advance → next scheduler run retries — **wired in Module 6**

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
  - Zero-interest accounts (no eligible txs) → client not called, cursor updates still returned for all accounts
  - Client throws `SynapsePostingException` → exception propagates, no cursor data returned
  - Partial success: account with 2 instructions, 1 rejected → that account's cursor excluded, other accounts' cursors included
  - All instructions for an account accepted → that account's cursor included
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

