# 02 — Replay Interest Posting Endpoint (Fineract Phase 1b)

## Context

Phase 1 (checklist 01 = `synapse-integration-implementation-checklist.md`) built the outbound path:
Fineract scheduler → calculates interest → sends batch to Synapse → advances cursor.

The Synapse proxy (separate repo) is **complete** — it receives the batch, posts to TigerBeetle,
and publishes a replay message back toward Fineract via RabbitMQ with
`command=replayInterestPosting`.

**This checklist builds the inbound replay endpoint** so Fineract can record the transaction
in its own ledger after Synapse has already processed it in TigerBeetle.

### What this endpoint does

1. Receives a replayed interest posting from Synapse (via queue → HTTP call)
2. Deduplicates on `traceId` (stored in `ref_no` column) — idempotent
3. Creates the correct `SavingsAccountTransaction` (INTEREST_POSTING / OVERDRAFT_INTEREST / WITHHOLD_TAX)
4. Updates `account_balance_derived`, `total_interest_posted_derived`
5. Creates journal entries
6. Updates running balances

### What this endpoint does NOT do

- Recalculate interest (already calculated by the scheduler)
- Check business rules (min balance, holds, overdraft limits)
- Advance posting cursor (already advanced when batch was dispatched in Phase 1)
- Go through maker-checker pipeline

### Request body (from Synapse)

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

`transactionType` is one of: `INTEREST_POSTING`, `OVERDRAFT_INTEREST`, `WITHHOLD_TAX`.

### URL

```
POST /api/v1/savingsaccounts/{savingsId}/transactions?command=replayInterestPosting
```

---

## Design Constraint: Non-Invasive, Testable Implementation

All new logic MUST follow the Phase 1 decomposition pattern:

1. **Extract core logic into `InterestPostingReplayService`** — a new, single-purpose class that owns dedup, type resolution, transaction construction, and balance arithmetic. The God class (`SavingsAccountWritePlatformServiceJpaRepositoryImpl`) gets a thin delegation method only.
2. **Constructor injection only, minimal dependency surface** — the extracted service depends only on `SavingsAccountTransactionRepository` (for dedup lookup). It does NOT inherit the 15+ dependencies of the God class.
3. **Testable without Spring context** — unit-testable with real domain objects and only repository mocked.
4. **Minimal modification surface** — each existing file gets ≤7 lines of change. No refactoring of existing methods or signatures.

### Phase 1 precedent (proof this pattern works)

| Extracted class | What it replaced | Test class |
|---|---|---|
| `SynapseInterestPostingService` | Inline logic in `batchUpdate()` | `SynapseInterestPostingServiceTest` |
| `SynapseInstructionMapper` | Would have been inline mapping | `SynapseInstructionMapperTest` (pure, no mocks) |
| `SynapseTransactionClient` | Would have been inline HTTP | `SynapseTransactionClientTest` (MockRestServiceServer) |

Apply the same decomposition here:

| Extracted class | What it replaces | Test class |
|---|---|---|
| `InterestPostingReplayService` | Would have been inline logic in God class | `InterestPostingReplayServiceTest` |

### Prior art — follow the DEPOSIT wiring pattern

The command routing layers mirror the existing deposit flow. The service layer diverges — logic goes into the extracted service, not the God class.

| Layer | Deposit reference file | What to copy |
|---|---|---|
| API Resource | `SavingsAccountTransactionsApiResource.transaction()` | Add `else if` branch for `replayInterestPosting` |
| CommandWrapperBuilder | `savingsAccountDeposit(Long)` | New method `savingsAccountReplayInterestPosting(Long)` |
| Command Handler | `DepositSavingsAccountCommandHandler` | New handler with `@CommandType(entity="SAVINGSACCOUNT", action="REPLAYINTERESTPOSTING")` |
| Service interface | `SavingsAccountWritePlatformService` | Add `replayInterestPosting(Long, JsonCommand)` |
| Service impl | `SavingsAccountWritePlatformServiceJpaRepositoryImpl` | **Thin delegate** — load account, call `InterestPostingReplayService`, persist, journal entries |
| **New: Extracted service** | `InterestPostingReplayService` | Dedup, type resolution, entity construction, balance arithmetic |
| Domain | `SavingsAccountTransaction.interestPosting()` / `.overdraftInterest()` / `.withHoldTax()` | Static factories already exist |
| Journal entries | `SavingsAccountDomainServiceJpa.postJournalEntriesForTransaction()` | Reuse directly |

---

## Module 1: `CommandWrapperBuilder`

**File:** `fineract-core/src/main/java/org/apache/fineract/commands/service/CommandWrapperBuilder.java`

- [ ] Add method `savingsAccountReplayInterestPosting(Long accountId)`:
  - `actionName = "REPLAYINTERESTPOSTING"`
  - `entityName = "SAVINGSACCOUNT"`
  - `savingsId = accountId`
  - `entityId = null`
  - `href = "/savingsaccounts/" + accountId + "/transactions"`
  - Pattern: identical to `savingsAccountDeposit()` (line ~1588) but different `actionName`

---

## Module 2: API Resource — route the command

**File:** `fineract-provider/.../savings/api/SavingsAccountTransactionsApiResource.java`

- [ ] In `transaction()` method (~line 178), add an `else if` branch:
  ```java
  } else if (is(commandParam, "replayInterestPosting")) {
      final CommandWrapper commandRequest = builder.savingsAccountReplayInterestPosting(savingsId).build();
      result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
  }
  ```
- [ ] Update the `UnrecognizedQueryParamException` array to include `"replayInterestPosting"`

---

## Module 3: Command Handler

**New file:** `fineract-provider/.../savings/handler/ReplayInterestPostingSavingsAccountCommandHandler.java`


- [ ] `@Service`, `@CommandType(entity = "SAVINGSACCOUNT", action = "REPLAYINTERESTPOSTING")`
- [ ] Implements `NewCommandSourceHandler`
- [ ] Inject `SavingsAccountWritePlatformService`
- [ ] `processCommand()` → `writePlatformService.replayInterestPosting(command.getSavingsId(), command)`
- [ ] `@Transactional`
- [ ] Pattern: identical to `DepositSavingsAccountCommandHandler` (~47 lines)

---

## Module 4: Service interface

**File:** `fineract-savings/.../savings/service/SavingsAccountWritePlatformService.java`

- [ ] Add method signature:
  ```java
  CommandProcessingResult replayInterestPosting(Long savingsId, JsonCommand command);
  ```

---

## Module 5: Extracted service — `InterestPostingReplayService`

**New file:** `fineract-savings/.../savings/service/synapse/InterestPostingReplayService.java`

This class owns ALL the replay business logic. It is a plain Java class with constructor injection — no `@Service` annotation (registered via `SavingsConfiguration` bean method, same as `SynapseInterestPostingService`).

### Constructor dependency

```java
@RequiredArgsConstructor
public class InterestPostingReplayService {
    private final SavingsAccountTransactionRepository transactionRepository;
}
```

Only one dependency — the transaction repository for dedup lookup. Everything else comes in as method parameters from the caller.

### 5a. Public method signature

- [ ] Method:
  ```java
  public ReplayResult replay(SavingsAccount account, String transactionType, BigDecimal transactionAmount,
          LocalDate transactionDate, BigDecimal overdraftAmount, String traceId)
  ```
- [ ] Returns a new inner record/class `ReplayResult`:
  ```java
  public record ReplayResult(SavingsAccountTransaction transaction, boolean alreadyExists) {}
  ```
- [ ] The caller (God class) decides what to do with each case (persist vs return early)

### 5b. Deduplicate on traceId

- [ ] Call `transactionRepository.findByRefNo(traceId)`
- [ ] If a non-reversed match exists for the same savings account → return `ReplayResult(existingTx, true)`
- [ ] Uses existing `ref_no` column — no schema change needed

### 5c. Resolve transaction type

- [ ] Map `transactionType` string to `SavingsAccountTransactionType`:
  - `"INTEREST_POSTING"` → `SavingsAccountTransactionType.INTEREST_POSTING` (value 3)
  - `"OVERDRAFT_INTEREST"` → `SavingsAccountTransactionType.OVERDRAFT_INTEREST` (value 17)
  - `"WITHHOLD_TAX"` → `SavingsAccountTransactionType.WITHHOLD_TAX` (value 18)
  - Anything else → throw `IllegalArgumentException`

### 5d. Create the transaction entity

- [ ] Use existing static factories on `SavingsAccountTransaction`:
  - For `INTEREST_POSTING`: `SavingsAccountTransaction.interestPosting(account, account.office(), date, money, false)`
  - For `OVERDRAFT_INTEREST`: `SavingsAccountTransaction.overdraftInterest(account, account.office(), date, money, false)`
  - For `WITHHOLD_TAX`: `SavingsAccountTransaction.withHoldTax(account, account.office(), date, money, emptyMap)`
- [ ] Set `refNo` to `traceId` via setter (see Module 7)

### 5e. Set overdraft amount (if applicable)

- [ ] If `transactionType` is `OVERDRAFT_INTEREST` and `overdraftAmount` is not null:
  - Set `overdraftAmount` on the transaction entity via `updateOverdraftAmount(BigDecimal)` or setter

### 5f. Update account summary balances

- [ ] CREDIT (INTEREST_POSTING): add to `account_balance_derived`, add to `total_interest_posted_derived`
- [ ] DEBIT (OVERDRAFT_INTEREST, WITHHOLD_TAX): subtract from `account_balance_derived`
- [ ] Use `account.getSummary()` methods
- [ ] Set running balance on the transaction

### 5g. Return result

- [ ] Return `ReplayResult(transaction, false)`

---

## Module 5b: Thin delegation in God class

**File:** `fineract-provider/.../savings/service/SavingsAccountWritePlatformServiceJpaRepositoryImpl.java`

This method is a **thin orchestrator** — no business logic, just wiring.

- [ ] Inject `InterestPostingReplayService` (add to constructor)
- [ ] Implement `replayInterestPosting(Long savingsId, JsonCommand command)`:

```java
public CommandProcessingResult replayInterestPosting(Long savingsId, JsonCommand command) {
    // 1. Parse command
    LocalDate txDate = command.localDateValueOfParameterNamed("transactionDate");
    BigDecimal txAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
    String txType = command.stringValueOfParameterNamed("transactionType");
    String traceId = command.stringValueOfParameterNamed("traceId");
    BigDecimal overdraftAmount = command.bigDecimalValueOfParameterNamed("overdraftAmount");

    // 2. Load account (no business rule checks)
    SavingsAccount account = savingAccountAssembler.assembleFrom(savingsId, false);

    // 3. Delegate to extracted service
    ReplayResult result = interestPostingReplayService.replay(
            account, txType, txAmount, txDate, overdraftAmount, traceId);

    // 4. If idempotent hit, return early
    if (result.alreadyExists()) {
        return new CommandProcessingResultBuilder()
                .withEntityId(result.transaction().getId())
                .withSavingsId(savingsId).build();
    }

    // 5. Persist
    savingsAccountTransactionRepository.saveAndFlush(result.transaction());
    savingAccountRepositoryWrapper.saveAndFlush(account);

    // 6. Journal entries
    postJournalEntries(account, result.transaction());

    // 7. Return
    return new CommandProcessingResultBuilder()
            .withEntityId(result.transaction().getId())
            .withSavingsId(savingsId)
            .withOfficeId(account.officeId())
            .withClientId(account.clientId()).build();
}
```

- [ ] The `postJournalEntries` helper reuses existing pattern:
  ```java
  SavingsAccountingBridgeDTO bridgeData = SavingsAccountingBridgeDataHelper.buildAccountingBridgeData(
      account, List.of(transaction), false);
  journalEntryWritePlatformService.createJournalEntriesForSavings(bridgeData, account.office());
  ```

### Dependencies used (all already injected in the class)

- `savingAccountAssembler` — load account
- `savingsAccountTransactionRepository` — persist transaction
- `savingAccountRepositoryWrapper` — persist account
- `journalEntryWritePlatformService` — journal entries
- **New:** `interestPostingReplayService` — the extracted service (add to constructor)

---

## Module 5c: Bean registration

**File:** `fineract-provider/.../savings/starter/SavingsConfiguration.java`

- [ ] Add bean method (same pattern as `SynapseInterestPostingService`):
  ```java
  @Bean
  @ConditionalOnProperty(prefix = "fineract.synapse", name = "enabled", havingValue = "true")
  public InterestPostingReplayService interestPostingReplayService(
          SavingsAccountTransactionRepository transactionRepository) {
      return new InterestPostingReplayService(transactionRepository);
  }
  ```
- [ ] Inject into `SavingsAccountWritePlatformServiceJpaRepositoryImpl` via `ObjectProvider<InterestPostingReplayService>` (optional dependency — replay only available when synapse is enabled)

---

## Module 6: Permission seed data

**New Liquibase migration:** `fineract-savings/src/main/resources/db/changelog/tenant/module/savings/parts/parts/2006_add_replay_interest_posting_permission.xml`

- [ ] Insert permission row:
  ```sql
  INSERT INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker)
  VALUES ('transaction_savings', 'REPLAYINTERESTPOSTING_SAVINGSACCOUNT', 'SAVINGSACCOUNT', 'REPLAYINTERESTPOSTING', 0);
  ```
- [ ] Register in `module-changelog-master.xml`

---

## Module 7: Entity change — `setRefNo`

**File:** `fineract-savings/.../savings/domain/SavingsAccountTransaction.java`

- [ ] Add method:
  ```java
  public void setRefNo(final String refNo) {
      this.refNo = refNo;
  }
  ```
- [ ] Needed because static factories set `refNo = null` and we need `traceId` for idempotency

---

## Module 8: Tests

### 8a. `InterestPostingReplayServiceTest` — the primary test class

**New file:** `fineract-savings/src/test/java/.../savings/service/synapse/InterestPostingReplayServiceTest.java`

Tests the extracted service directly. Real domain objects, only `SavingsAccountTransactionRepository` mocked.

- [ ] INTEREST_POSTING happy path: returns `ReplayResult(tx, false)`, tx has correct type (value 3), `refNo = traceId`, account balance increased
- [ ] OVERDRAFT_INTEREST happy path: debit type (value 17), `overdraftAmount` set, account balance decreased
- [ ] WITHHOLD_TAX happy path: debit type (value 18), account balance decreased
- [ ] Idempotency: mock `findByRefNo(traceId)` to return existing tx → returns `ReplayResult(existingTx, true)`, no static factory called
- [ ] Unknown `transactionType` string → `IllegalArgumentException`

### 8b. What NOT to test (and why)

- `CommandWrapperBuilder` — trivial builder, no logic
- `SavingsAccountTransactionsApiResource` routing — integration test territory
- `ReplayInterestPostingSavingsAccountCommandHandler` — one-line delegation
- `SavingsAccountWritePlatformServiceJpaRepositoryImpl.replayInterestPosting()` — thin orchestrator, covered by integration tests; testing it would require mocking 15+ constructor args for no value

---

## Execution Order

```
Module 7 (setRefNo on entity)           ← smallest, no deps
Module 1 (CommandWrapperBuilder)         ← no deps
Module 4 (Service interface)             ← no deps
Module 6 (Permission migration)          ← no deps
  ↓
Module 5  (InterestPostingReplayService) ← depends on 7
Module 5c (Bean registration)            ← depends on 5
Module 3  (Command handler)              ← depends on 4
  ↓
Module 5b (God class thin delegate)      ← depends on 4, 5, 5c
Module 2  (API Resource routing)         ← depends on 1
  ↓
Module 8  (Tests)                        ← depends on 5, 7
```

Modules 1, 4, 6, 7 are independent — can be done in parallel.
Module 8 can be written alongside Module 5 (they share the same new class).

---

## Key files reference

| File | Why |
|---|---|
| `SynapseInterestPostingService.java` | **Primary template** — same decomposition pattern for the new extracted service |
| `SynapseInterestPostingServiceTest.java` | **Primary test template** — real mapper, mock client |
| `SavingsConfiguration.java` | Bean registration pattern (line ~480) |
| `DepositSavingsAccountCommandHandler.java` | Template for new handler |
| `SavingsAccountWritePlatformServiceJpaRepositoryImpl.deposit()` | Template for thin delegation (but simpler) |
| `SavingsAccountDomainServiceJpa.postJournalEntriesForTransaction()` | Reuse for journal entries |
| `SavingsAccountTransaction.interestPosting()` | Static factory |
| `SavingsAccountTransactionRepository.findByRefNo()` | Idempotency lookup |
| `SavingsAccountTransactionType` enum | INTEREST_POSTING(3), OVERDRAFT_INTEREST(17), WITHHOLD_TAX(18) |
| `SavingsAccountingBridgeDataHelper.buildAccountingBridgeData()` | Journal entry DTO builder |

---

## Predecessor documents

- `01` = `synapse-integration-implementation-checklist.md` — Phase 1 outbound (✅)
- `synapse-proxy-endpoint-handoff.md` — Synapse batch contract (✅)
- `interest-posting-synapse-integration-plan.md` — Architecture overview
- Synapse checklist: `fin-proxy-interest-posting-v2/.augment/plans/interest-posting-checklist.md` (✅)