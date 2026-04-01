# 03 — Replay Interest Posting: Integration Test + Gate Direct Posting

## Context

Checklist 02 built the replay endpoint. Unit tests cover the extracted service and the thin delegate
in isolation. **Nothing proves the full wiring works through the command pipeline** — that the API
resource routes the command, the permission exists, Spring finds the handler via `@CommandType`,
the `ObjectProvider<InterestPostingReplayService>` resolves, the transaction persists to the DB,
journal entries are created, and the balance is updated.

Additionally, when `fineract.synapse.enabled=true`, the per-account `postInterest` and
`postInterestAsOn` API endpoints create a **split-brain problem**: they write interest transactions
directly to the DB, bypassing Synapse/TigerBeetle. TigerBeetle doesn't know about those
transactions, so balances diverge. These endpoints must be gated when synapse is enabled.

This checklist has three parts:
1. **Gate direct interest posting endpoints** when synapse is enabled (code change)
2. **WireMock stub for Synapse** so the Cargo integration test server can call the batch endpoint
3. **Integration tests** proving the full wiring

---

## Part A: Gate direct interest posting when synapse is enabled

### Problem

These two API paths bypass Synapse entirely when `fineract.synapse.enabled=true`:

| Endpoint | Route | Handler | Service method |
|---|---|---|---|
| `POST /savingsaccounts/{id}?command=postInterest` | `SavingsAccountsApiResource.handleCommands()` line 531 | `PostInterestSavingsAccountCommandHandler` | `writePlatformService.postInterest(command)` |
| `POST /savingsaccounts/{id}/transactions?command=postInterestAsOn` | `SavingsAccountTransactionsApiResource.transaction()` line 192 | `PostSavingsAccountInterestAsOnDateCommandHandler` | `writePlatformService.postInterest(command)` |

Both call `SavingsAccountWritePlatformServiceJpaRepositoryImpl.postInterest(JsonCommand)` (line 509)
which calculates interest and writes transactions directly to `m_savings_account_transaction` —
without going through Synapse. This creates a balance split-brain with TigerBeetle.

### Fix

**File:** `fineract-provider/.../savings/service/SavingsAccountWritePlatformServiceJpaRepositoryImpl.java`

- [x] In `postInterest(JsonCommand command)` (~line 509), add an early guard at the top of the method:
  ```java
  @Override
  @Transactional
  public CommandProcessingResult postInterest(final JsonCommand command) {
      if (interestPostingReplayServiceProvider.getIfAvailable() != null) {
          throw new PlatformServiceUnavailableException(
              "error.msg.direct.interest.posting.disabled.when.synapse.enabled",
              "Direct interest posting is disabled when Synapse is enabled. "
              + "Interest is posted via the scheduler batch job and replayed from Synapse.");
      }
      // ... existing code unchanged
  }
  ```
- [x] This reuses the existing `ObjectProvider<InterestPostingReplayService>` field (already injected
  in checklist 02). When the bean exists → synapse is enabled → block.
- [x] The `postInterest(SavingsAccount, boolean, LocalDate, boolean)` overload (line 559) is NOT gated —
  it's called by the scheduler batch path internally and by other domain services. Only the
  `JsonCommand` entry point (user-facing API) is blocked.
- [x] The `postInterest(SavingsAccountData, ...)` overload (line 609) also needs the same guard —
  this is the batch/scheduler variant called from `SavingsSchedularInterestPoster`. However, when
  synapse is enabled, `batchUpdate()` takes the synapse path and never calls this method. Verify
  this is true by checking `SavingsSchedularInterestPoster.batchUpdate()` line 170-176. If so,
  **no guard needed on that overload** — it's already unreachable when synapse is on.

### What this does NOT block

- `calculateInterest` command — this just calculates, doesn't persist. Safe to keep.
- `SavingsAccount.postInterest()` domain method — called by other flows (withdrawals, deposits that
  trigger recalculation). These don't go through the command API and are internal to Fineract.
- The scheduler batch path — `batchUpdate()` already has its own `isSynapseEnabled()` guard.

---

## Part B: Cargo + WireMock setup for Synapse stub

### B1. Enable synapse and point to WireMock in Cargo JVM args

**File:** `integration-tests/build.gradle`

- [x] In the Cargo `containerProperties` block (~line 75), append to `jvmArgs`:
  ```groovy
  jvmArgs += ' -Dfineract.synapse.enabled=true'
  jvmArgs += ' -Dfineract.synapse.base-url=http://localhost:18089'
  ```
  Port 18089 is arbitrary — chosen to avoid conflicts. This is the port WireMock will listen on.
  The batch endpoint path is already configured as `/api/v1/proxy/savings/interest-postings:batch`
  via `fineract.synapse.batch-endpoint` (default in `application.properties`).

### B2. WireMock stub in test class

The WireMock runs in the test JVM process. The Cargo Fineract server (same host, different JVM)
calls `http://localhost:18089/api/v1/proxy/savings/interest-postings:batch`. This is the same
pattern used by `CreditBureauTest` (WireMock on port 3558).

- [x] In the test class, use `WireMockExtension` with fixed port:
  ```java
  @RegisterExtension
  static WireMockExtension synapse = WireMockExtension.newInstance()
      .options(wireMockConfig().port(18089))
      .build();
  ```

- [x] In `@BeforeEach`, configure the default stub:
  ```java
  synapse.stubFor(WireMock.post(urlEqualTo("/api/v1/proxy/savings/interest-postings:batch"))
      .willReturn(WireMock.aResponse()
          .withStatus(200)
          .withHeader("Content-Type", "application/json")
          .withBody("{\"batchId\":\"stub\",\"accepted\":1,\"failed\":0,\"results\":[]}")
      ));
  ```
  This catch-all stub returns success for any batch posted. Individual tests can override it
  or add more specific stubs.

- [x] For tests that need to verify what Fineract sent to Synapse, use `synapse.verify()`:
  ```java
  synapse.verify(postRequestedFor(urlEqualTo("/api/v1/proxy/savings/interest-postings:batch"))
      .withRequestBody(containing("\"traceId\"")));
  ```

---

## Part C: Helper methods on `SavingsAccountHelper`

**File:** `integration-tests/.../common/savings/SavingsAccountHelper.java`

- [x] Add method `replayInterestPosting(Integer savingsId, String jsonBody)`:
  ```java
  @Deprecated(forRemoval = true)
  public Integer replayInterestPosting(final Integer savingsId, final String jsonBody) {
      final String url = createSavingsTransactionURL("replayInterestPosting", savingsId);
      return (Integer) performSavingActions(url, jsonBody, CommonConstants.RESPONSE_RESOURCE_ID);
  }
  ```

- [x] Add static method `buildReplayInterestPostingJson(...)`:
  ```java
  public static String buildReplayInterestPostingJson(String amount, String date,
          String transactionType, String traceId, String overdraftAmount) {
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

## Part D: Test class

**New file:** `integration-tests/src/test/java/org/apache/fineract/integrationtests/ReplayInterestPostingIntegrationTest.java`

- [x] `@ExtendWith({ SavingsTestLifecycleExtension.class })`
- [x] `@RegisterExtension static WireMockExtension synapse` on port 18089
- [x] Standard `@BeforeEach`: `Utils.initializeRESTAssured()`, RequestSpec, ResponseSpec, SavingsAccountHelper, SchedulerJobHelper
- [x] In `@BeforeEach`: register default synapse WireMock stub (returns 200 with empty success response)

### Test 1: Happy path — replay INTEREST_POSTING creates transaction and updates balance

- [x] Create client, savings product (daily posting, CASH_BASED accounting), apply/approve/activate
- [x] Deposit seed funds (e.g., `1000`)
- [x] Record balance before replay via `getSavingsSummary(savingsId).get("accountBalance")`
- [x] Call `replayInterestPosting()` with:
  - `transactionAmount = "50.00"`, `transactionType = "INTEREST_POSTING"`
  - `traceId = UUID.randomUUID().toString()`, `transactionDate = activation date`
- [x] Assert: returned `resourceId` is not null
- [x] Assert: `accountBalance` = previous balance + 50.00
- [x] Fetch transaction details via `getTransactionDetails(savingsId, transactionId)`
- [x] Assert: `transactionType.interestPosting == true`

### Test 2: Idempotency — same traceId returns same transaction, no duplicate

- [x] Same setup as Test 1
- [x] Call `replayInterestPosting()` with fixed `traceId` — record `resourceId`
- [x] Call again with **same `traceId`**
- [x] Assert: second call returns the **same `resourceId`**
- [x] Assert: balance reflects only ONE posting

### Test 3: OVERDRAFT_INTEREST replay creates debit transaction

- [x] Create savings product with **overdraft enabled**
- [x] Create/activate account, deposit, then withdraw more than balance (overdraft position)
- [x] Call `replayInterestPosting()` with `transactionType = "OVERDRAFT_INTEREST"`, `overdraftAmount`, unique `traceId`
- [x] Assert: balance **decreased**
- [x] Assert: transaction type is overdraft interest

### Test 4: WITHHOLD_TAX replay creates debit transaction

- [x] Create savings product with **withhold tax enabled** (requires tax group setup)
- [x] Create/activate account, deposit seed funds
- [x] Call `replayInterestPosting()` with `transactionType = "WITHHOLD_TAX"`, unique `traceId`
- [x] Assert: balance **decreased**
- [x] Assert: transaction type is withhold tax

### Test 5: Unknown transactionType returns error

- [x] Create and activate account
- [x] Build replay JSON with `transactionType = "INVALID_TYPE"`
- [x] Use `ResponseSpec` expecting 500 status
- [x] Assert error response

### Test 6: Direct `postInterest` blocked when synapse enabled

- [x] Create/activate account, deposit seed funds
- [x] Call `savingsAccountHelper.postInterestForSavings(savingsId)` using a `ResponseSpec` expecting 503
- [x] Assert: error response contains `"error.msg.direct.interest.posting.disabled.when.synapse.enabled"`
- [x] This proves Part A gating works end-to-end

### Test 7: Direct `postInterestAsOn` blocked when synapse enabled

- [x] Same setup as Test 6
- [x] Call `savingsAccountHelper.postInterestAsOnSavings(savingsId, today)` with `ResponseSpec` expecting 503
- [x] Assert: same error as Test 6

### Test 8: Scheduler job → WireMock captures batch → manual replay completes the loop

This is the **end-to-end test** proving the full outbound+inbound flow:

- [x] Create savings product with daily posting (compound interest, CASH_BASED accounting)
- [x] Create client, apply/approve/activate account, deposit `1000`
- [x] Set business date far enough in the future that interest accrues (or use a start date in the past)
- [x] Configure WireMock to capture the batch request body:
  ```java
  synapse.stubFor(WireMock.post(urlEqualTo("/api/v1/proxy/savings/interest-postings:batch"))
      .willReturn(WireMock.aResponse()
          .withStatus(200)
          .withHeader("Content-Type", "application/json")
          .withTransformers("response-template")  // if needed
          .withBody(buildSuccessResponse())
      ));
  ```
- [x] Run the scheduler job: `schedulerJobHelper.executeAndAwaitJob("Post Interest For Savings")`
- [x] Verify WireMock received the batch POST:
  ```java
  synapse.verify(1, postRequestedFor(
      urlEqualTo("/api/v1/proxy/savings/interest-postings:batch")));
  ```
- [x] Extract the request body from WireMock logs to get the `traceId` and `amount` for each instruction
- [x] For each instruction in the captured batch, call `replayInterestPosting()` with matching fields
- [x] Verify final balance = deposit + total interest posted
- [x] Verify number of interest posting transactions matches number of instructions in the batch

**Why this test matters:** It proves:
  1. The scheduler correctly sends interest to Synapse (WireMock) instead of writing to DB
  2. The replay endpoint correctly records those transactions in Fineract's ledger
  3. The final balance is consistent after the full round-trip
  4. No duplicate transactions exist

### Extracting traceId from WireMock captured requests

```java
List<LoggedRequest> requests = synapse.findAll(
    postRequestedFor(urlEqualTo("/api/v1/proxy/savings/interest-postings:batch")));
String batchBody = requests.get(0).getBodyAsString();
// Parse JSON to extract transactions[].traceId, transactions[].amount, etc.
JsonPath batchJson = JsonPath.from(batchBody);
List<Map<String, Object>> instructions = batchJson.getList("transactions");
```

### Building WireMock success response matching the batch

The WireMock stub needs to return `SynapseBatchPostingResponse` shape:
```json
{
  "batchId": "<from request>",
  "accepted": <count>,
  "failed": 0,
  "results": [
    { "traceId": "<from instruction>", "status": "ACCEPTED", "correlationId": "syn-..." },
    ...
  ]
}
```

For simplicity, use a static response with `accepted = 999, failed = 0, results = []`.
The `SynapseInterestPostingService.filterByResponse()` handles the case where results list
doesn't contain a specific traceId — verify this defaults to success or failure.
If it defaults to failure, then the WireMock response must include matching results.

Check `SynapseInterestPostingService.filterByResponse()` logic before choosing the stub shape.

---

## Execution order

```
Part A  (Gate postInterest)              ← code change, no test deps
  ↓
Part B1 (Cargo JVM args)                 ← build config
Part C  (Helper methods)                 ← no deps
  ↓
Part B2 (WireMock in test class)         ← depends on B1
Part D  (Tests 1-5: replay endpoint)     ← depends on B2, C
Part D  (Tests 6-7: gating)              ← depends on A, B2
Part D  (Test 8: scheduler round-trip)   ← depends on A, B2, C
```

Parts A, B1, and C are independent — can be done in parallel.

---

## What NOT to test

- Synapse disabled (bean absent) → `PlatformServiceUnavailableException` — would require a separate
  Cargo server without the flag. Environmental concern, not business logic.
- Journal entry GL account mapping — verified by existing accounting integration tests.
- `calculateInterest` command — intentionally NOT gated, still allowed when synapse enabled.

---

## Execution notes

- Run with: `./gradlew :integration-tests:test --tests "*ReplayInterestPostingIntegrationTest*"`
- Cargo starts/stops Tomcat automatically unless `-PcargoDisabled` is set.
- Requires a running MariaDB/MySQL/Postgres (same as all other integration tests).
- WireMock runs in the test JVM on port 18089 — Cargo Fineract server calls `localhost:18089`.
- Template: `CreditBureauTest.java` (WireMock + Cargo pattern), `SavingsAccountBalanceCheckAfterReversalTest.java` (savings setup pattern).

---

## Key files reference

| File | Why |
|---|---|
| `CreditBureauTest.java` | **Template** — WireMock + Cargo integration pattern |
| `SavingsAccountBalanceCheckAfterReversalTest.java` | **Template** — savings setup/assertions |
| `SavingsInterestPostingIntegrationTest.java` | **Template** — scheduler job + interest posting |
| `SavingsAccountHelper.java` | Add helpers; `performSavingActions()` + `createSavingsTransactionURL()` |
| `SavingsProductHelper.java` | Create savings products with accounting |
| `SavingsAccountsApiResource.handleCommands()` | Where `postInterest` is routed (line 531) |
| `SavingsAccountTransactionsApiResource.transaction()` | Where `postInterestAsOn` is routed (line 192) |
| `SavingsAccountWritePlatformServiceJpaRepositoryImpl.postInterest(JsonCommand)` | Gate target (line 509) |
| `SynapseInterestPostingService.filterByResponse()` | Determines how WireMock response must be shaped |
| `integration-tests/build.gradle` | Cargo JVM args (line 75) |
| `integration-tests/dependencies.gradle` | WireMock already available: `wiremock-standalone` |

---

## Predecessor documents

- `01` = `synapse-integration-implementation-checklist.md` — Phase 1 outbound (✅)
- `02` = `02-replay-interest-posting-endpoint-checklist.md` — Replay endpoint (✅)
- Synapse checklist: `fin-proxy-interest-posting-v2/.augment/plans/interest-posting-checklist.md` (✅)
