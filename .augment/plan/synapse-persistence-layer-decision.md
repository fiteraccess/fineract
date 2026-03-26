# Synapse Integration — Local Persistence Layer Decision

## Question

Does Fineract need a new table (e.g. `m_synapse_posting_instruction`) to track outbound
Synapse requests and responses, or is the current stateless DTO approach sufficient?

## Current State (Phase 1)

After `postInterestBatch()` returns, the `traceId`, `batchId`, `correlationId`, and Synapse
response are garbage-collected. The only durable artifacts are:

- `interest_posted_till_date` / `last_interest_calculation_date` cursor on `m_savings_account`
- Application log lines (DEBUG level)

Fineract already has a local outbox pattern — `m_external_event` with `idempotencyKey`,
`status`, `sentAt` — but it is not used by the Synapse integration.

---

## Evaluation by Dimension

### 1. Auditability

**Gap:** No durable record that interest of ₦250.00 for account 12345 was dispatched to
Synapse with `traceId = abc-123` at 02:01:07 on March 20th. The cursor tells you interest
was posted *through* a date, not *how many times* or *via which batch*.

**Impact:** Regulatory inquiries require a chain from Fineract calculation → dispatch →
settlement. The middle link is missing. When queue replay creates the transaction row in
`m_savings_account_transaction`, there is no way to correlate it back to the originating
scheduler run or Synapse batch.

**Mitigation without table:** Structured JSON logging shipped to an indexed store (e.g.
Elasticsearch) — queryable, but requires log aggregation infrastructure.

### 2. Error Recovery & Idempotency

**Current mechanism:** HTTP failure → exception propagates → cursor not advanced → next
scheduler run recalculates → sends new batch with new `traceId`s → Synapse deduplicates.

**Gap — double-posting window:** Between `postBatch()` return (line 174) and
`executeCursorUpdates()` (line 175), if the DB write fails or the process crashes, Synapse
has accepted the instructions but Fineract will re-send them next run with **new** `traceId`s.
Synapse sees them as new. This is a double-posting risk.

| Scenario | Current behaviour | With table |
|---|---|---|
| Synapse 200 but cursor UPDATE fails | Re-sends with new traceIds → double posting | Write instruction records in same DB tx as cursor; retry reuses same traceIds |
| Process crash between HTTP and cursor | Same as above | Same fix — write-ahead or same-transaction |
| Partial success, transient rejection | Retry with new traceIds; original may also process | Records which traceIds were accepted/rejected; retry reuses originals |

### 3. Reconciliation

**Gap:** No mechanism to detect when Fineract's ledger and Synapse/TigerBeetle disagree.
If queue replay is delayed, ops must query Synapse's API to determine what was dispatched.
Phase 3 (reversals) will generate compensating postings — without a record of what was
originally sent, determining correct compensating amounts requires re-deriving from account
state that may have changed.

**With table:** Reconciliation becomes a SQL join between `m_synapse_posting_instruction`
and `m_savings_account_transaction`.

### 4. Traceability

**Gap:** When queue replay writes a transaction into `m_savings_account_transaction`, no
column links it to the originating `traceId` or `batchId`. Support cannot answer "this
transaction came from Synapse batch XYZ, instruction traceId ABC."

**With table:** `SELECT * FROM m_synapse_posting_instruction WHERE savings_account_id = ?
AND posting_date = ?` — no log aggregation dependency.

---

## Recommendation

**Introduce the table immediately before Phase 3 (Reversals), not now.**

| Factor | Phase 2 (Manual Posting) | Phase 3 (Reversals) |
|---|---|---|
| Double-posting risk | Narrow window; manageable by monitoring | Reversals compound duplicate consequences |
| Audit trail | Structured logging sufficient | Reversals demand "what was originally sent" lookups |
| Reconciliation | Synapse is sole source of truth; acceptable | Compensating postings need original per-instruction amounts |
| Traceability | DEBUG logs + Synapse records suffice | Multiple paths (scheduler, manual, reversal) make log tracing brittle |
| Complexity cost | Table adds migration, JPA, repository, tests — delays Phase 2 | Schema is clearer once reversal flows are designed |

**Phase 2** uses the same `SynapseInterestPostingService` with the same data-flow shape.
Adding manual posting doesn't change the trade-off.

**Phase 3** changes the calculus: a reversal needs to reference the *original* instruction's
`traceId` to send a compensating `REVERSE` to Synapse. Today `originalTransactionId` maps
to Fineract's transaction ID — but in the Synapse path, that ID doesn't exist yet (created
during queue replay). A local table gives the reversal flow a reliable lookup source.

---

## Proposed Schema (for Phase 3)

```sql
CREATE TABLE m_synapse_posting_instruction (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id                VARCHAR(36)   NOT NULL UNIQUE,
    batch_id                VARCHAR(36)   NOT NULL,
    savings_account_id      BIGINT        NOT NULL,
    transaction_type        VARCHAR(30)   NOT NULL,  -- INTEREST_POSTING, OVERDRAFT_INTEREST, WITHHOLD_TAX
    operation               VARCHAR(10)   NOT NULL,  -- POST, REVERSE
    amount                  DECIMAL(19,6) NOT NULL,
    posting_date            DATE          NOT NULL,
    status                  VARCHAR(15)   NOT NULL,  -- DISPATCHED, ACCEPTED, REJECTED, REPLAYED
    synapse_correlation_id  VARCHAR(64),
    fineract_transaction_id BIGINT,                  -- populated on queue replay
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_synapse_posting_account (savings_account_id, posting_date),
    INDEX idx_synapse_posting_batch (batch_id)
);
```

No raw JSON payloads — structured columns are sufficient and queryable. The `status` column
progresses through `DISPATCHED → ACCEPTED → REPLAYED`, closing the full lifecycle.

