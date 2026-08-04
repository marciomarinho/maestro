# 0004. Transactional outbox with a polling relay, not CDC

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

When a merchant confirms a payment, two things must happen: the payment row moves to `AUTHORIZING`, and a command to authorize is published to Kafka. They must happen together.

If the database commits and the publish fails, the payment is stuck in `AUTHORIZING` forever and the merchant's customer is left staring at a spinner. If the publish succeeds and the database rolls back, the router authorizes a payment the system has no record of — money moves against a phantom.

The two resources are a database and a message broker. There is no shared transaction between them, and the usual answer — a distributed transaction — is not available in practice and would not be desirable if it were.

## Decision

The **transactional outbox** pattern. The event is inserted into an `outbox_event` table in the *same database transaction* as the state change. The write is atomic because it is a single database transaction. A separate **polling relay** then reads unpublished rows and publishes them to Kafka.

The relay:

- Claims batches with `SELECT ... FOR UPDATE SKIP LOCKED`, so multiple service instances cooperate without a leader election
- Publishes, then marks rows published — in that order, so a crash between the two causes a duplicate, never a loss
- Preserves per-aggregate ordering by claiming rows for a given aggregate in sequence
- Carries the trace context stored on the outbox row, so the asynchronous hop stays inside the same distributed trace

This gives at-least-once publication. Duplicates are handled by consumer-side idempotency; see [ADR-0006](0006-exactly-once-effects.md).

The relay runs in-process within each service that owns an outbox, rather than as a separate deployable.

## Consequences

**Positive.** State change and event publication cannot diverge. No distributed transaction, no two-phase commit. The relay is simple enough to reason about completely. Because the outbox table is in the service's own schema, the boundary is not violated.

**Negative.** Publication latency is bounded by the polling interval — single-digit milliseconds with a short interval, at the cost of steady database load. Mitigated by an in-process notification that wakes the relay immediately after a commit, with polling as the safety net rather than the primary path. The outbox table also needs sweeping, or it grows without bound.

**Neutral.** Every consumer must be idempotent. This is not really a cost, because consumers must be idempotent anyway to survive broker redelivery and consumer restarts.

## Alternatives considered

### Change data capture with Debezium

Reads the write-ahead log and publishes changes with no polling and lower latency. This is the correct answer at high scale, and it is the documented evolution path.

Rejected for now because it adds Debezium and Kafka Connect to a stack that must run on a laptop, it moves a correctness-critical component outside the services that depend on it, and it couples the event contract to the physical table schema unless an outbox table is used anyway — at which point most of the benefit is latency. The trade-off is deliberate and stated rather than accidental.

### Publish first, then write to the database

Rejected: a database failure after a successful publish means the platform has instructed an authorization it has no record of. In payments, the direction of the failure matters, and this is the wrong direction.

### Write to the database, then publish, best effort

The common shortcut, and it works most of the time. Rejected because "most of the time" over a million payments is a meaningful number of stuck transactions, and because there is no way to detect or recover the lost ones after the fact.

### A distributed transaction across PostgreSQL and Kafka

Kafka does not participate in XA. Not available.

## Revisit when

Publication latency or database load from polling becomes a measured bottleneck in a load test — at which point CDC is the answer, and the outbox table can remain as the event contract.
