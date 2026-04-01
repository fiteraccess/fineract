# 03 — Replay Interest Posting: Integration Test

## Context

Checklist 02 built the replay endpoint. Unit tests cover the extracted service and the thin delegate
in isolation. **Nothing proves the full wiring works through the command pipeline** — that the API
resource routes the command, the permission exists, Spring finds the handler via `@CommandType`,
the `ObjectProvider<InterestPostingReplayService>` resolves, the transaction persists to the DB,
journal entries are created, and the balance is updated.

This test uses the existing Cargo-based integration test infrastructure that starts a real Fineract
server with a real database.

---

## Prerequisite: Enable synapse for integration test server

**File:** `integration-tests/build.gradle`

- [ ] In the Cargo `containerProperties` block (~line 75), add synapse flag to `jvmArgs`:
  ```groovy
  jvmArgs += ' -Dfineract.synapse.enabled=true'
  ```
  This goes on the same line that already has `-Dspring.profiles.active=test -Dfineract.events.external.enabled=true`.
  Without this, `ObjectProvider<InterestPostingReplayService>` returns null and the endpoint throws
  `PlatformServiceUnavailableException`.

---

## Helper method on `SavingsAccountHelper`

**File:** `integration-tests/.../common/savings/SavingsAccountHelper.java`

- [ ] Add method `replayInterestPosting(Integer savingsId, String jsonBody)`:
  ```java
  @Deprecated(forRemoval = true)
  public Integer replayInterestPosting(final Integer savingsId, final String jsonBody) {
      final String url = createSavingsTransactionURL("replayInterestPosting", savingsId);
      return (Integer) performSavingActions(url, jsonBody, CommonConstants.RESPONSE_RESOURCE_ID);
  }
  ```
  Pattern: identical to `depositToSavingsAccount(savingsId, jsonBody, RESPONSE_RESOURCE_ID)`.
  Uses existing `createSavingsTransactionURL()` and `performSavingActions()`.

- [ ] Add method `buildReplayInterestPostingJson(...)` for constructing the request body:
  ```java
  public static String buildReplayInterestPostingJson(String amount, String date, String transactionType,
          String traceId, String overdraftAmount) {
      HashMap<String, Object> map = new HashMap<>();
      map.put("transactionDate", date);
      map.put("transactionAmount", amount);
      map.put("transactionType", transactionType);
      map.put("traceId", traceId);
      map.put("locale", CommonConstants.LOCALE);
      map.put("dateFormat", CommonConstants.DATE_FORMAT);
      if (overdraftAmount != null) {
          map.put("overdraftAmount", overdraftAmount);
      }
      return new Gson().toJson(map);
  }
  ```

---

## Test class

**New file:** `integration-tests/src/test/java/org/apache/fineract/integrationtests/ReplayInterestPostingIntegrationTest.java`

- [ ] `@ExtendWith({ SavingsTestLifecycleExtension.class })`
- [ ] Standard `@BeforeEach` setup: `Utils.initializeRESTAssured()`, `RequestSpec`, `ResponseSpec`, `SavingsAccountHelper`

### Test 1: Happy path — INTEREST_POSTING replay creates transaction and updates balance

- [ ] Create client via `ClientHelper.createClient()`
- [ ] Create savings product via `SavingsProductHelper` (daily posting, CASH_BASED accounting)
- [ ] Apply for savings account, approve, activate
- [ ] Deposit seed funds (e.g., `1000`) via `depositToSavingsAccount()` — establishes a known starting balance
- [ ] Record balance before replay via `getSavingsSummary(savingsId).get("accountBalance")`
- [ ] Call `replayInterestPosting()` with:
  - `transactionAmount = "50.00"`
  - `transactionType = "INTEREST_POSTING"`
  - `traceId = UUID.randomUUID().toString()`
  - `transactionDate = activation date`
- [ ] Assert: returned `resourceId` is not null (transaction was created)
- [ ] Fetch summary again via `getSavingsSummary(savingsId)`
- [ ] Assert: `accountBalance` = previous balance + 50.00
- [ ] Fetch transaction details via `getTransactionDetails(savingsId, transactionId)`
- [ ] Assert: transaction type is interest posting (`transactionType.interestPosting == true`)

### Test 2: Idempotency — same traceId returns existing transaction, no duplicate

- [ ] Same setup as Test 1 (create client → product → account → activate → deposit)
- [ ] Call `replayInterestPosting()` with a fixed `traceId` — record returned `resourceId`
- [ ] Call `replayInterestPosting()` again with the **same `traceId`**, same amount, same date
- [ ] Assert: second call returns the **same `resourceId`** as first call
- [ ] Fetch summary — balance should reflect only ONE posting, not two

### Test 3: OVERDRAFT_INTEREST replay creates debit transaction

- [ ] Create savings product with **overdraft enabled**
- [ ] Create and activate account
- [ ] Deposit seed funds, then withdraw more than balance (to create overdraft position)
- [ ] Call `replayInterestPosting()` with:
  - `transactionType = "OVERDRAFT_INTEREST"`
  - `overdraftAmount = <some value>`
  - unique `traceId`
- [ ] Assert: balance **decreased** (overdraft interest is a debit)
- [ ] Assert: transaction type is overdraft interest

### Test 4: WITHHOLD_TAX replay creates debit transaction

- [ ] Create savings product with **withhold tax enabled** (requires tax group setup)
- [ ] Create and activate account, deposit seed funds
- [ ] Call `replayInterestPosting()` with `transactionType = "WITHHOLD_TAX"`
- [ ] Assert: balance **decreased**
- [ ] Assert: transaction type is withhold tax

### Test 5: Unknown transactionType returns error

- [ ] Create and activate account
- [ ] Build replay JSON with `transactionType = "INVALID_TYPE"`
- [ ] Use a `ResponseSpec` expecting 400/500 status
- [ ] Call replay and assert error response

---

## What NOT to test

- Synapse disabled (bean absent) → `PlatformServiceUnavailableException` — this is environment config,
  not business logic. Testing it would require a separate Cargo server without the flag.
- Journal entry GL account mapping — this is verified by existing accounting integration tests.
  The replay uses the same journal entry path as native interest posting.

---

## Execution notes

- Run with: `./gradlew :integration-tests:test --tests "*ReplayInterestPostingIntegrationTest*"`
- The Cargo plugin starts/stops Tomcat automatically unless `-PcargoDisabled` is set.
- These tests require a running MariaDB/MySQL/Postgres (same as all other integration tests).
- The test class follows the exact same structure as `SavingsAccountBalanceCheckAfterReversalTest`.

---

## Key files reference

| File | Why |
|---|---|
| `SavingsAccountBalanceCheckAfterReversalTest.java` | **Template** — same setup/teardown pattern |
| `SavingsAccountHelper.java` | Add helper method here; `performSavingActions()` + `createSavingsTransactionURL()` |
| `SavingsProductHelper.java` | Create savings product with accounting |
| `Utils.performServerPost()` | Low-level REST-Assured POST |
| `CommonConstants.RESPONSE_RESOURCE_ID` | JSON attribute to extract from response |
| `integration-tests/build.gradle` | Cargo JVM args — add `-Dfineract.synapse.enabled=true` |

## Predecessor documents

- `01` = `synapse-integration-implementation-checklist.md` — Phase 1 outbound (✅)
- `02` = `02-replay-interest-posting-endpoint-checklist.md` — Replay endpoint (✅)
- Synapse checklist: `fin-proxy-interest-posting-v2/.augment/plans/interest-posting-checklist.md` (✅)
