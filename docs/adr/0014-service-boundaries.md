# 0014. Four deployables, and the test each split had to pass

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

Maestro is built by one person. That single fact invalidates the most common
justification for splitting a system into services.

The primary benefit of microservices is *organisational*: independent teams shipping
on independent cadences without coordinating a release. There are no teams here.
Every other claimed benefit — independent scaling, fault isolation, technology
choice — is real but far narrower, and each is paid for in network calls, eventual
consistency, distributed debugging and operational surface area.

The correct default for a system of this size is therefore **a modular monolith**,
and any deviation needs a specific reason. "It is a distributed system" is not a
reason; it is a description of the cost.

## Decision

**Four deployables: `payment-api`, `router`, `ledger`, `acquirer-sim`.** Each split
had to pass an explicit test, and the test does not include organisational
independence.

> A boundary is justified only if the two halves differ in at least one of:
> **failure domain** (one must survive the other dying), **scaling axis** (they scale
> on different signals), or **security boundary** (they need different data rights).

Applied:

| Boundary | Failure domain | Scaling axis | Security boundary | Verdict |
|---|---|---|---|---|
| `acquirer-sim` | It stands in for a third party across a network | — | — | **Mandatory** |
| `router` ÷ `payment-api` | Slow, unreliable outbound I/O and Kafka consumer-group membership must not affect merchant-facing availability | Request rate vs. payment volume × acquirer latency | — | **Justified** |
| `ledger` ÷ `payment-api` | Must be rebuildable by replay without API downtime | Reconciliation is batch work that would compete with merchant traffic | Only component permitted to write postings; runs under a role with `INSERT`/`SELECT` grants and nothing else | **Justified** |

`acquirer-sim` is not really an architectural choice. It simulates an institution
reached over a network, and the entire premise of the project depends on being able
to make that network misbehave. A timeout, a connection reset or a brownout cannot be
demonstrated against an in-process method call (ADR-0011).

The `ledger` split rests mainly on the security boundary. Postings are append-only by
database grant (ADR-0008), and that guarantee is materially weaker when the API's
connection pool lives in the same JVM as the ledger's.

### Splits that were explicitly rejected

**A service per entity** — `payment-service`, `refund-service`, `merchant-service`,
`webhook-service`, `notification-service`. This is the cargo-cult shape, and it fails
the test on every axis. Refunds share the payment aggregate's invariants
(*refunded never exceeds captured*), so separating them would require a distributed
transaction to enforce a rule a single `UPDATE` currently enforces for free. Entities
are not service boundaries; failure domains are.

**Splitting reconciliation out of `ledger`** — it reads and writes the same tables
under the same invariants. A boundary there would be a network call inside one
transaction's worth of work.

### The split is kept reversible

Domain logic lives in modules that know nothing about transport. Service classes are
thin shells over them: a controller or a listener that adapts a request into a call.
Merging two services would be a build-file change and a configuration change, not a
rewrite. This is stated here so that it stays true — an ArchUnit rule keeps framework
types out of `lib-domain`, and any service-specific logic that grows a dependency on
`spring-web` inside its application layer is a signal the shell has stopped being thin.

## Consequences

**Positive.** Each process has one runtime profile, so a failure in one is
comprehensible in isolation. An acquirer hanging cannot consume merchant-facing
request capacity. The ledger's append-only guarantee is enforced by a database role
rather than by developer discipline. And the distributed failure modes this project
exists to demonstrate — outbox, at-least-once delivery, idempotent consumers,
per-payment ordering — are genuinely present rather than simulated.

**Negative.** Four JVMs plus Kafka, PostgreSQL and the observability stack is roughly
4–6 GB of memory on a developer laptop; the figure is published in the README rather
than left to be discovered, and the observability stack sits behind a Compose profile
so the everyday loop stays light. Every cross-service interaction is eventually
consistent, and a reader must hold four processes in their head. Debugging spans
processes, which is why distributed tracing is a Phase 4 deliverable rather than a
nicety.

**Neutral.** The monorepo means a cross-cutting change is still one commit and one CI
run (ADR-0001); the deployment topology is split, the development workflow is not.

## Alternatives considered

### A modular monolith with `acquirer-sim` separate

Two deployables, around 500 MB, simplest possible operations, fastest inner loop —
and, for a single author building a real product, **the right answer**. It is worth
being unambiguous about that rather than defending the split on general principle.

Rejected here because it would remove the subject matter. The transactional outbox
exists precisely because a database and a message broker cannot share a transaction.
Idempotent consumers exist because delivery is at-least-once. Guarded state
transitions exist because events can be redelivered. In a single process, all three
collapse into one method call inside one transaction — and the problems this project
was built to demonstrate stop existing. The distribution is not incidental
complexity; it is the artifact.

The distinction worth holding onto: *this is a system built to demonstrate
distributed-systems engineering, not a startup's first product.* If it were the
latter, this record would reach the opposite conclusion.

### Folding `ledger` into `payment-api`

Three deployables, one fewer JVM. Rejected because it weakens the append-only role
separation to a convention within a single process, and because reconciliation batch
work would then compete with merchant request traffic on the same heap and pool.
Every payments platform of any maturity separates the money book, and for this
reason.

### The same modules deployed two ways

A `monolith` profile booting all three services in one process from the same modules,
proving the reversibility claim instead of asserting it. Genuinely attractive, and
the strongest possible answer to "is this really modular?".

Rejected on cost: it roughly doubles the integration-test surface, since every
behaviour would need verifying in both topologies, and a second topology that is not
continuously tested is worse than no second topology. Recorded in the backlog.

## Revisit when

The memory footprint becomes an obstacle to running the platform on a typical
laptop, or a component's runtime profile changes such that it no longer differs from
its neighbour on any of the three axes.
