# 0013. Idempotency records in PostgreSQL, not a cache

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

Merchants retry. Networks drop responses after the server has already acted, load balancers time out mid-request, and client libraries retry automatically on `5xx`. In a payments API, a retry that creates a second authorization takes a customer's money twice.

The standard remedy is an idempotency key: the merchant supplies one, and a repeat with the same key returns the original result rather than repeating the effect. The question is where that record lives, and it is more consequential than it first appears.

The failure to avoid is a **window between the effect and the record of it**. If the payment is created and the idempotency key is stored afterwards, a crash in between leaves an effect with no record — and the merchant's retry, finding no key, creates a second payment. A design that is correct only when no process ever crashes at an inconvenient moment is not correct.

## Decision

Idempotency records live in **PostgreSQL, written in the same transaction as the effect they guard**.

```sql
BEGIN;
  INSERT INTO idempotency_record (merchant_id, key, endpoint, request_hash, status)
  VALUES (?, ?, ?, ?, 'IN_PROGRESS');        -- unique constraint claims the key
  INSERT INTO payment (...);                  -- the effect
  INSERT INTO outbox_event (...);             -- the intent to publish
COMMIT;
```

One transaction, three writes, atomic. There is no window in which the payment exists without its idempotency record, because they commit together or not at all. The same transaction also carries the outbox insert ([ADR-0004](0004-transactional-outbox.md)), so the entire request is a single atomic unit.

Semantics:

- **Scope** is merchant + endpoint + key, enforced by a unique constraint. Concurrency control is the constraint itself: two simultaneous requests race to insert, and exactly one wins.
- The **response body and status are stored** on the record and replayed with `Idempotency-Replayed: true`.
- A replay with a **different request fingerprint** returns `409`, not the original response. Silently returning the first response to a genuinely different request would hide a real merchant defect behind a success.
- A replay while the original is **still in progress** returns `409` with `Retry-After`.
- Records are retained for **24 hours**, then swept — long enough to cover any plausible client retry schedule.

## Consequences

**Positive.** Atomicity is a property of the transaction, not of careful ordering. No second datastore in the request path, so no second failure mode and one fewer component in the local stack. The idempotency record is queryable alongside the payment it guarded, which makes support investigations straightforward. Recovery after a crash needs no reconciliation, because there is nothing to reconcile.

**Negative.** Two additional row writes per state-changing request and a table that must be swept. At payments volumes this is immaterial — the request is already writing a payment row and an outbox row in the same transaction, and the index on the unique constraint is small and hot.

**Neutral.** The retention window is a stated contract with merchants rather than an implementation detail, and appears in the API documentation.

## Alternatives considered

### Redis with `SETNX`

The reflexive answer, and fast. Rejected because it cannot participate in the database transaction, which reintroduces exactly the window this decision exists to close: either the key is claimed before the effect (and a crash leaves a key blocking a payment that was never created) or after it (and a crash leaves a payment with no key, so the retry duplicates it). Both are recoverable only by compensating logic that is harder to get right than the problem it solves.

It also adds a stateful component to the request path. Redis being unavailable would mean either failing every write request or proceeding without idempotency protection — and the second option is how duplicate charges happen during an unrelated incident.

Speed is not the constraint here. A payments API is bounded by the acquirer round trip, not by an extra local index lookup.

### An application-level lock or in-memory map

Rejected: does not survive a restart and does not span instances.

### A unique constraint on a natural business key instead

For example, unique on `(merchant_id, reference)`. Rejected because the merchant's reference is not guaranteed unique, legitimately repeats for retried orders, and does not carry a stored response to replay. It also does not generalise to endpoints that do not create a resource, such as capture.

### No idempotency, relying on merchants not to retry

Rejected. Merchants retry, and so do their HTTP clients, often without the merchant knowing.

## Revisit when

Idempotency-record writes appear in a load-test profile as a measurable bottleneck — at which point partitioning the table by time is a better first move than relocating it to a cache.
