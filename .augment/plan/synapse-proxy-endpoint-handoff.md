# Synapse Proxy — Batch Interest Posting Endpoint (Handoff)

## Context

Fineract's interest-posting scheduler has been modified (Phase 1 complete) to send
calculated interest postings to Synapse instead of writing directly to its own database.
The Fineract side is fully built and tested. **Synapse needs one new endpoint** to receive
these batched instructions.

### Architecture

```
Fineract scheduler (runs nightly)
  → calculates interest for N accounts
  → collects posting instructions into a batch
  → POST to Synapse batch endpoint      ← YOU BUILD THIS
  → Synapse fans out to TigerBeetle
  → Synapse queues events back to Fineract for ledger catch-up
```

Fineract does NOT insert transaction rows or update balances locally. It only advances a
date cursor (`interest_posted_till_date`) after Synapse confirms acceptance. If Synapse
rejects or the call fails, Fineract retries on the next scheduler run.

---

## Endpoint Contract

### URL

```
POST {synapse-base}/api/v1/proxy/savings/interest-postings:batch
```

Fineract is configured to call `{baseUrl}{batchEndpoint}` where:
- `baseUrl` = e.g. `http://synapse-proxy:8080`
- `batchEndpoint` = `/api/v1/proxy/savings/interest-postings:batch`

### Headers

```
Content-Type: application/json
```

### Request Body

```json
{
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "postingDate": "2026-03-20",
  "totalCount": 3,
  "transactions": [
    {
      "traceId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "savingsAccountId": 12345,
      "officeId": 10,
      "externalId": "EXT-12345",
      "transactionType": "INTEREST_POSTING",
      "direction": "CREDIT",
      "operation": "POST",
      "amount": 250.00,
      "overdraftAmount": null,
      "transactionDate": "2026-03-20",
      "currencyCode": "NGN",
      "refNo": "S12345_20260320_I",
      "originalTransactionId": null,
      "batchId": "550e8400-e29b-41d4-a716-446655440000"
    },
    {
      "traceId": "8a1b2c3d-4e5f-6789-abcd-ef0123456789",
      "savingsAccountId": 12345,
      "officeId": 10,
      "externalId": "EXT-12345",
      "transactionType": "WITHHOLD_TAX",
      "direction": "DEBIT",
      "operation": "POST",
      "amount": 25.00,
      "overdraftAmount": null,
      "transactionDate": "2026-03-20",
      "currencyCode": "NGN",
      "refNo": "S12345_20260320_T",
      "originalTransactionId": null,
      "batchId": "550e8400-e29b-41d4-a716-446655440000"
    },
    {
      "traceId": "11223344-5566-7788-99aa-bbccddeeff00",
      "savingsAccountId": 67890,
      "officeId": 10,
      "externalId": null,
      "transactionType": "OVERDRAFT_INTEREST",
      "direction": "DEBIT",
      "operation": "POST",
      "amount": 15.50,
      "overdraftAmount": 15.50,
      "transactionDate": "2026-03-20",
      "currencyCode": "NGN",
      "refNo": null,
      "originalTransactionId": null,
      "batchId": "550e8400-e29b-41d4-a716-446655440000"
    }
  ]
}
```

### Field Reference — `transactions[]`

| Field | Type | Required | Description |
|---|---|---|---|
| `traceId` | string (UUID) | ✅ | **Idempotency key.** Synapse MUST deduplicate on this. If a `traceId` has already been processed, return `ACCEPTED` again — do not double-post. |
| `savingsAccountId` | long | ✅ | Fineract's internal savings account ID |
| `officeId` | long | ✅ | Branch/office owning the account |
| `externalId` | string | ❌ | External ID of the savings account, may be null |
| `transactionType` | enum | ✅ | `INTEREST_POSTING`, `OVERDRAFT_INTEREST`, or `WITHHOLD_TAX` |
| `direction` | enum | ✅ | `CREDIT` (money into account) or `DEBIT` (money out of account) |
| `operation` | enum | ✅ | `POST` (new posting) or `REVERSE` (undo a prior posting) |
| `amount` | decimal | ✅ | Always positive. `direction` determines sign. |
| `overdraftAmount` | decimal | ❌ | Populated for `OVERDRAFT_INTEREST` — the overdraft portion |
| `transactionDate` | date | ✅ | The value date for the posting (ISO 8601: `YYYY-MM-DD`) |
| `currencyCode` | string | ✅ | ISO 4217 currency code (e.g. `NGN`, `USD`) |
| `refNo` | string | ❌ | Reference number from Fineract, may be null |
| `originalTransactionId` | long | ❌ | Set when `operation=REVERSE` — the Fineract transaction being reversed |
| `batchId` | string (UUID) | ✅ | Same as the top-level `batchId` — groups this instruction with the batch |

### Response Body (HTTP 200)

Synapse MUST return a result for **every** `traceId` in the request. The `results` array
must be the same length as the request's `transactions` array.

```json
{
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "accepted": 2,
  "failed": 1,
  "results": [
    {
      "traceId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "status": "ACCEPTED",
      "correlationId": "syn-tx-98765"
    },
    {
      "traceId": "8a1b2c3d-4e5f-6789-abcd-ef0123456789",
      "status": "REJECTED",
      "correlationId": null
    },
    {
      "traceId": "11223344-5566-7788-99aa-bbccddeeff00",
      "status": "ACCEPTED",
      "correlationId": "syn-tx-98766"
    }
  ]
}
```

### Response Field Reference

| Field | Type | Description |
|---|---|---|
| `batchId` | string | Echo of the request's `batchId` |
| `accepted` | int | Count of instructions with `status=ACCEPTED` |
| `failed` | int | Count of instructions with `status=REJECTED` |
| `results` | array | One entry per instruction, same order as request |
| `results[].traceId` | string | Echo of the instruction's `traceId` |
| `results[].status` | string | `ACCEPTED` or `REJECTED` |
| `results[].correlationId` | string | Synapse's internal transaction ID (nullable on rejection) |

### Error Responses

| HTTP Status | When | Fineract Behaviour |
|---|---|---|
| `200` | Batch processed (may have partial failures) | Advances cursor for accepted accounts only |
| `4xx` | Bad request / validation error | Throws exception, no cursor advanced, entire batch retried next run |
| `5xx` | Synapse internal error | Throws exception, no cursor advanced, entire batch retried next run |
| Timeout | No response within 30s (configurable) | Throws exception, no cursor advanced, entire batch retried next run |

On any non-200, Fineract treats the **entire batch** as failed. There is no partial
recovery from HTTP errors — only from per-instruction `REJECTED` statuses within a 200.

---

## Critical Requirements

### 1. Idempotency on `traceId`

This is the single most important requirement. If Synapse receives a `traceId` it has
already processed:
- Return `status: "ACCEPTED"` for that instruction
- Do NOT create a duplicate posting in TigerBeetle
- Do NOT return an error

This handles Fineract retries after crashes or network failures.

### 2. Atomicity

Each instruction is independent. Synapse should process as many as possible and report
individual results. A failure in one instruction must NOT cause other instructions in the
batch to fail.

### 3. One account, multiple instructions

A single account may have 2–3 instructions in the same batch (interest credit + withhold
tax debit, or interest credit + overdraft interest debit). These are logically related
but should be processed independently. Fineract handles the correlation — if any
instruction for an account is rejected, Fineract excludes that entire account from cursor
advancement and retries all its instructions next run.

### 4. Queue replay back to Fineract

After processing each instruction in TigerBeetle, Synapse must call back into Fineract
so it can record the transaction in its own ledger. **Fineract will expose a replay
endpoint that follows the same pattern as its existing deposit/withdrawal endpoints.**

Do NOT use the existing `?command=deposit` or `?command=withdrawal` endpoints — those
create the wrong transaction types and trigger business rules that don't apply.

**Fineract replay endpoint (Fineract will build this):**

```
POST {fineract-base}/api/v1/savingsaccounts/{savingsAccountId}/transactions?command=replayInterestPosting
```

This follows the same URL pattern as deposits and withdrawals:
- Deposit: `POST /v1/savingsaccounts/{id}/transactions?command=deposit`
- Withdrawal: `POST /v1/savingsaccounts/{id}/transactions?command=withdrawal`
- **Replay: `POST /v1/savingsaccounts/{id}/transactions?command=replayInterestPosting`**

**Request body Synapse should send (standard Fineract JSON command format):**

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

Note: `transactionDate` uses Fineract's standard date format with `dateFormat` and `locale`
fields, matching how deposit/withdrawal requests are structured.

**What Fineract does on receiving this (not Synapse's concern):**
- Deduplicates on `traceId` — if already replayed, returns 200 with existing transaction ID
- Inserts the row in `m_savings_account_transaction` with the correct
  `SavingsAccountTransactionType` (INTEREST_POSTING=3, OVERDRAFT_INTEREST=17, WITHHOLD_TAX=18)
- Updates `account_balance_derived`, `total_interest_posted_derived`
- Creates journal entries with the correct GL accounts (interest expense → savings)
- Updates running balances

**Synapse's responsibility:** After TigerBeetle confirms the posting, call this endpoint
(or queue the call for reliable delivery). The call should include the fields above.
Synapse does NOT need to know about Fineract's internal transaction types, GL accounts,
or journal entry logic — just forward the original instruction fields plus the
`correlationId` Synapse assigned.

**Important:** This endpoint does not exist yet. Fineract will build it. The Synapse
implementation should plan for this call but can stub it initially.

---

## Transaction Type Semantics

| `transactionType` | `direction` | What it means | TigerBeetle effect |
|---|---|---|---|
| `INTEREST_POSTING` | `CREDIT` | Accrued interest paid into savings account | Credit the savings account |
| `OVERDRAFT_INTEREST` | `DEBIT` | Interest charged on overdraft balance | Debit the savings account |
| `WITHHOLD_TAX` | `DEBIT` | Tax deducted from interest earned | Debit the savings account |

All amounts are positive. `direction` determines whether TigerBeetle credits or debits.

---

## Batch Characteristics

| Property | Typical Value |
|---|---|
| Batch size | 200 accounts per batch (Fineract's `ThreadPoolSize` config) |
| Instructions per batch | 200–600 (1–3 instructions per account) |
| Frequency | Nightly scheduler run (configurable CRON) |
| Currency | Single currency per deployment (e.g. `NGN`) |
| Amounts | Typically small (daily interest on savings) |

Synapse should be prepared for batches up to ~1000 instructions. Larger deployments may
increase the thread pool size.

---

## Testing Checklist for Synapse

- [ ] Happy path: batch of 3 instructions → all accepted → correct response
- [ ] Idempotency: send same `traceId` twice → second call returns `ACCEPTED`, no duplicate in TigerBeetle
- [ ] Partial failure: 1 instruction has invalid account → that one `REJECTED`, others `ACCEPTED`
- [ ] Empty batch: `totalCount=0`, `transactions=[]` → return `200` with `accepted=0, failed=0, results=[]`
- [ ] Mixed types: batch with `INTEREST_POSTING` + `WITHHOLD_TAX` for same account → both processed
- [ ] HTTP error simulation: Synapse returns 500 → Fineract retries next run
- [ ] Queue replay: accepted instruction → event published → Fineract consumer creates transaction row

---

## Fineract Configuration Reference

These are the Fineract-side settings (for context, not for Synapse to implement):

```yaml
fineract:
  synapse:
    enabled: true
    base-url: http://synapse-proxy:8080
    batch-endpoint: /api/v1/proxy/savings/interest-postings:batch
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
    retry-max-attempts: 3
    retry-backoff-ms: 1000
```

---

## Files to Reference (Fineract side — read-only context)

| File | What it contains |
|---|---|
| `fineract-savings/.../data/synapse/SynapseTransactionInstruction.java` | Request DTO with all enums |
| `fineract-savings/.../data/synapse/SynapseInterestPostingBatch.java` | Batch wrapper DTO |
| `fineract-savings/.../data/synapse/SynapseBatchPostingResponse.java` | Expected response DTO |
| `fineract-savings/.../data/synapse/SynapsePostingResult.java` | Per-instruction result DTO |
| `fineract-savings/.../service/synapse/SynapseTransactionClient.java` | HTTP client (shows what Fineract sends) |
| `fineract-savings/.../service/synapse/SynapseInstructionMapper.java` | Maps domain objects → instructions |
| `fineract-savings/src/test/.../SynapseTransactionClientTest.java` | Shows exact serialized JSON shapes |

