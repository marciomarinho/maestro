# Operations

How Maestro is run, observed and recovered. Runbooks are written **with** the feature that can fail, not after the first incident — a runbook that exists only after something has already broken at 2am is a post-mortem action item, not an operational practice.

---

## Operational posture

**Every failure mode this system is designed to survive has a runbook.** If a component can fail in a way that requires human judgement, the judgement is written down before it is needed.

**Alerts fire on symptoms, not causes.** "Authorization success rate below floor" is actionable and matters to a merchant. "Acquirer B CPU above eighty percent" is neither.

**Every runbook names its signal, its likely causes, its diagnosis and its remedy** — and states explicitly what *not* to do, which is usually the more valuable half.

**Money problems are never fixed with an `UPDATE`.** Ledger postings are append-only by database grant; a correction is a reversing transaction with a recorded approval. Any runbook proposing to edit financial data directly is wrong by construction.

---

## Runbooks

Each lands with its phase. None are written speculatively for components that do not yet exist.

| Runbook | Signal | Phase |
|---|---|---|
| [Dead-letter queue growth](runbooks/dead-letter-queue-growth.md) | DLQ depth rising | 4 |
| [Ledger drift detected](runbooks/ledger-drift.md) | Balance verification reports non-zero drift | 2 |
| [Acquirer brownout](runbooks/acquirer-brownout.md) | Corridor health score collapse, success rate dip | 3 |
| [Circuit breaker stuck open](runbooks/circuit-breaker-stuck-open.md) | Breaker open beyond its expected probe cycle | 3 |
| [Consumer lag](runbooks/consumer-lag.md) | Kafka consumer group lag above threshold | 4 |
| [Database saturation](runbooks/database-saturation.md) | Connection pool exhaustion, statement latency | 4 |
| [Outbox relay stalled](runbooks/outbox-relay-stalled.md) | Unpublished outbox rows ageing | 4 |
| Reconciliation discrepancy triage | Unmatched settlement lines, drift metric | 5 |
| Webhook delivery failure | Merchant endpoint failing repeatedly | 5 |

The [template](runbook-template.md) defines the required structure.

---

## Service level objectives

Stated as targets the load tests and dashboards are built to measure. They are the definition of "working", and each maps to a dashboard panel.

| Objective | Target | Why this number |
|---|---|---|
| Payment creation latency, p99 | < 150 ms | The merchant's checkout is waiting; the acquirer call is asynchronous, so this measures only Maestro |
| Authorization end-to-end, p95 | < 2 s | Bounded by the acquirer, plus platform overhead |
| Authorization success rate | Within 2 points of the best available acquirer's rate | This is the router's whole purpose, and the number the brownout demo defends |
| Time to shift traffic away from a degrading acquirer | < 30 s | Faster than a human could notice and act |
| Ledger drift | Exactly zero | Not an SLO with a budget; any drift is an incident |
| Reconciliation completeness | 100% of settlement lines classified | Unexplained is not a permitted state |

---

## Observability

**Traces.** One payment produces one connected trace across all four services, propagated through HTTP headers, the outbox row and Kafka headers. Every trace carries `payment_id`, `merchant_id` and the acquirer attempts.

**Metrics.** Prometheus, with a naming convention defined in `lib-observability` and a constants class that makes an ad-hoc metric name a code-review failure. The core families are the payments funnel by status, per-corridor health and routing split, ledger integrity, and the golden signals per service.

**Logs.** Structured ECS JSON in containers, with `traceId`, `payment_id` and `merchant_id` on every line (`LogContext` scopes the payment fields; the tracing bridge contributes the trace). Bodies are logged through a **field allow-list rather than a deny-list**, so a newly added sensitive field is excluded by default rather than leaked until someone notices.

**Dashboards** are committed as provisioned JSON and reproducible from a cold start. A dashboard that exists only in someone's Grafana is not an operational asset.

---

## The demo and chaos instruments

`acquirer-sim` exposes a fault-injection API under `/admin`, deliberately unauthenticated and confined to the simulator — it exists to be broken. It is the instrument behind both the demo scripts and the load scenarios: decline rates, latency distributions, timeouts, throughput caps, brownout and blackout, and (from Phase 5) injectable settlement discrepancies.

Toxiproxy sits between the services and their dependencies for infrastructure-level faults — database latency, Kafka unavailability, network partitions — used in the Phase 4 chaos experiments, each of which is published with a hypothesis, an observation and whether the hypothesis held.

---

## Recovery primitives

| Situation | Mechanism |
|---|---|
| Poison message | Automatic retry with backoff, then the dead-letter topic; redrive through `POST /ops/dlq/redrive` after the cause is fixed |
| Consumer defect after processing | Rewind the consumer group and replay; every consumer is idempotent, so replay is safe |
| Ledger projection defect | Rebuild from the event stream; `source_event_id` uniqueness makes the rebuild idempotent |
| Acquirer misbehaving | Disable the corridor (`acquirer_corridor.enabled = FALSE`); traffic reroutes on the next decision |
| Stuck payment | Attempt history shows where it stopped; the reconciliation path resolves genuinely unknown outcomes |
| Bad financial record | A reversing journal transaction with a recorded approval. Never an update |

Replay safety is not a hopeful property here. It is the direct consequence of the idempotency mechanisms in [ADR-0006](../adr/0006-exactly-once-effects.md), which is what makes "rewind and replay" a routine operation rather than a frightening one.
