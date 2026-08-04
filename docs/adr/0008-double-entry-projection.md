# 0008. A double-entry ledger as a projection, not event sourcing

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

The ledger is the authoritative record of money. It must satisfy properties that ordinary application state does not:

- It must always balance, and be **provably** so rather than believed to be so.
- It must be immutable. An auditor expects corrections as reversing entries, not as updated rows.
- It must be reconstructible, so a defect in a projection can be repaired by rebuilding.
- It must survive replayed events without double-counting.

Two designs are conventionally proposed. One is event sourcing with the ledger as an aggregate rebuilt from its own event stream. The other is a relational double-entry ledger fed by domain events.

There is also a modelling question that is more consequential than the storage choice, and easier to get wrong: **what an authorization does to the books.**

## Decision

**A relational double-entry ledger, written as a projection of the Kafka event stream, with invariants enforced by the database.**

### Double-entry, enforced by constraints

Every money movement is a `journal_transaction` containing two or more `posting` rows. Postings within a transaction must sum to exactly zero per currency, enforced by a database constraint checked at commit — not by application code, which can be bypassed by the next person who writes a repository method.

Postings are **append-only**: the application's database role holds `INSERT` and `SELECT` grants and nothing else. `UPDATE` and `DELETE` are not permitted to fail a code review; they are impossible. A correction is a reversing transaction referencing the original.

Idempotence comes from a unique constraint on `journal_transaction.source_event_id`. A replayed event violates it and is skipped. The ledger cannot double-post even if every other layer of the system fails.

### Authorizations are holds, not postings

**An authorization does not move money.** It reserves funds on the cardholder's account and produces a promise. Nothing has been transferred, and nothing is owed to the merchant.

Modelling authorizations as postings is the most common way a payments ledger goes wrong. It inflates receivables and merchant balances with money that nobody has, and every expired or voided authorization then requires a compensating entry — so the books carry a running population of phantom amounts that must be swept. Systems built this way tend to discover the problem when a merchant's dashboard balance stops matching what they can actually be paid.

Authorizations are therefore `hold` records with their own lifecycle (`ACTIVE`, `CAPTURED`, `RELEASED`, `EXPIRED`). Postings begin at capture, which is when money actually moves. The full event-to-posting mapping is in [the domain model](../domain.md#4-how-events-become-postings).

### A projection, not a source of truth about events

The Kafka event stream is the immutable log of what happened. The ledger is a durable, rebuildable projection of it into a form that answers financial questions: balances, statements, reconciliation.

### Verification rather than assumption

Balances are materialised for query performance, and a verification job periodically recomputes them from raw postings and compares. Any divergence raises a drift metric and pages. Correctness is measured continuously rather than assumed from the fact that the code once passed a test.

## Consequences

**Positive.** The critical invariant is a database constraint, so it holds regardless of application defects. Immutability gives a genuine audit trail. Balances are a simple aggregation, and reconciliation against acquirer settlement files is a straightforward join. Any engineer or accountant who has seen a general ledger can read this schema immediately.

**Negative.** Balance queries aggregate over postings, requiring materialisation and therefore the verification job that keeps it honest. Reversing entries rather than corrections means the posting table only grows. Rebuilding the projection requires event-stream retention long enough to replay, which is a stated operational requirement rather than an assumption.

**Neutral.** The ledger lags the payment record slightly, since it consumes asynchronously. This is correct — the ledger records what happened, and it records it after it happened.

## Alternatives considered

### Event sourcing with the ledger as an aggregate

Rebuild balances by replaying the ledger's own events; store nothing but events.

Rejected, and this is the more interesting half of the decision. **The audit properties event sourcing is chosen for are already present here**: an immutable event log exists in Kafka, and the projection is rebuildable from it. What event sourcing would add is a framework — snapshotting, event upcasting for schema evolution, aggregate boundaries and concurrency control, and a rebuild story that must be exercised regularly to be trustworthy — in exchange for benefits this system already has by other means.

There is also a specific mismatch. Event-sourced aggregates are strongly consistent within an aggregate boundary, and the natural boundary here — a merchant's balance — is exactly the object that must accept high-concurrency writes from unrelated payments. That makes the aggregate a contention point, solvable with the usual techniques, but the usual techniques are complexity added to solve a problem the relational design does not have.

Adopting event sourcing here would be resume-driven design: the kind of decision that reads as sophistication to a junior reviewer and as a missing trade-off analysis to a senior one. The honest position — *the event log is the source of truth, the ledger is a rebuildable projection, and we did not need the framework* — is the stronger engineering statement.

### Single-entry — a running balance per merchant

A `balance` column updated on each transaction. Rejected: it cannot answer "why is the balance this number", it has no audit trail, it is a lock contention point, and it makes reconciliation impossible. This is how a first version is usually built and how the first serious discrepancy becomes unfindable.

### A specialised ledger database such as TigerBeetle

Purpose-built for double-entry accounting at very high throughput, with the invariants built into the engine. A genuinely good fit for the problem, and noted in the backlog.

Rejected because PostgreSQL constraints already enforce the invariants at this scale, and because introducing a second, unfamiliar datastore would spend the reader's attention on infrastructure rather than on design. Worth revisiting if throughput ever justified it.

## Revisit when

Posting volume makes aggregate balance queries slow enough that materialisation plus verification no longer suffices, or a regulatory requirement demands a ledger engine with formal guarantees.
