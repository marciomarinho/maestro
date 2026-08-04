# 0006. At-least-once delivery with exactly-once money effects

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

"Exactly-once" is the property everyone wants in a payments system and the one most often claimed without qualification. It is worth being precise about what is achievable.

Kafka offers exactly-once *processing* semantics through transactional producers and consumers — but only for effects that live inside Kafka, or inside a resource that participates in the same transaction. Maestro's most important effects do not: authorizing a payment is an HTTP call to an external bank. No broker transaction can span it. If the platform sends an authorization request and the connection drops before the response arrives, no amount of transactional machinery inside Kafka can tell whether the bank authorized it.

The honest framing is therefore not "how do we get exactly-once delivery" but **"how do we make duplicate delivery harmless."**

## Decision

**Delivery is at-least-once everywhere. Money effects are exactly-once, achieved by making every effect idempotent at its own boundary.**

Four mechanisms, each covering a different failure:

**1. Guarded state transitions.** Every payment state change is a conditional update predicated on the current state:

```sql
UPDATE payment SET status = 'AUTHORIZED', ... 
WHERE id = ? AND status = 'AUTHORIZING'
```

A duplicate event updates zero rows and is acknowledged without effect. The database row is the concurrency-control point; no application-level lock is involved.

**2. Deduplication on `event_id`.** Every event envelope carries a unique `event_id`. Consumers whose effect is not naturally guarded record processed identifiers and skip repeats.

**3. Unique constraints on natural keys.** The ledger's `journal_transaction.source_event_id` is unique. A replayed capture event violates the constraint and is skipped. The ledger cannot double-post even if every other layer fails, because the guarantee is a database constraint rather than a code path.

**4. Deterministic acquirer idempotency keys.** Each attempt sends a key derived from `(payment_id, attempt_no)`. A retry within an attempt reuses the key, so the acquirer returns the original result rather than authorizing again. A *failover* to a different acquirer increments `attempt_no` — a genuinely different operation against a different institution.

**Acknowledgement is manual and follows the effect.** A consumer commits its offset only after its effect is durable. A crash between the two causes redelivery, which mechanisms one to three make harmless.

**The unknown-outcome case.** When an acquirer call times out, the outcome is genuinely unknown — the bank may have authorized. The attempt is recorded as `TIMEOUT`, and the retry carries the same idempotency key so the acquirer resolves the ambiguity. If the acquirer cannot, the payment is marked for reconciliation and the settlement file becomes the arbiter. This is exactly how real payment systems handle it: reconciliation is not a back-office afterthought, it is the final consistency mechanism.

## Consequences

**Positive.** Correctness does not depend on delivery guarantees the infrastructure cannot provide across an external HTTP boundary. Each mechanism is independently testable, and the strongest — the database constraint — needs no code to be correct. Consumers can be restarted, rewound and replayed safely, which makes operational recovery routine rather than frightening.

**Negative.** Every consumer author must think about idempotency; the framework does not do it for them. Mitigated by making the guarded-update pattern the only way state changes are written, and by ArchUnit rules over repository methods.

**Neutral.** Deduplication state must be swept. The acquirer idempotency contract must be honoured by `acquirer-sim`, which is deliberate — it is what makes the retry logic testable at all.

## Alternatives considered

### Kafka transactional producers and consumers

Genuine exactly-once for Kafka-to-Kafka flows. Rejected as the primary mechanism because the effects that matter are external HTTP calls and PostgreSQL writes, neither of which participates. Adopting it would add operational complexity and produce a system that is *still* not exactly-once where it counts, while creating a false sense that it is. The transactional outbox already provides the atomicity that matters ([ADR-0004](0004-transactional-outbox.md)).

### At-most-once — acknowledge before processing

Never double-charges. Rejected because it drops payments on any crash, and a silently lost payment is worse than a duplicate: a duplicate is detectable and refundable, a loss is invisible until the customer complains.

### A distributed lock per payment

Would serialise processing. Rejected because it adds a coordination service, introduces a new failure mode when the lock service is unavailable, and solves a problem the database row already solves through conditional updates.

## Revisit when

Never, in the sense that this is a property of the domain rather than of the implementation. Any future component that introduces an effect must state which of the four mechanisms makes it idempotent.
