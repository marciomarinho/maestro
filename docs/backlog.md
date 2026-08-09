# Backlog — deliberately not built

Everything here was considered and consciously deferred. The list exists for two reasons: so that a reader can tell the difference between an omission and a decision, and so that new ideas have somewhere to go that is not the build.

**Scope is a contract.** Adding to this file is free. Adding to the roadmap is not.

Each entry states what it is, why it was deferred, and what would justify building it.

---

## Domain

### Chargebacks and dispute management
The full lifecycle: retrieval request, chargeback, representment, arbitration, with the ledger movements and deadlines each stage carries.
**Deferred because** it is a substantial domain in its own right that would double the state machine without adding a new *class* of engineering problem — the interesting mechanics are the same event-driven, ledger-backed patterns already demonstrated by refunds and reconciliation.
**Build when** the reconciliation story is complete and a distinct engineering problem is needed. The vocabulary is already in [the domain model](domain.md) so the extension is additive.

### Multi-currency accounts and foreign exchange
Merchants settling in several currencies, with conversion at capture or at payout.
**Deferred because** conversion introduces rate sourcing, rate freshness, and rounding across currencies — a correctness domain deserving proper treatment rather than a bolt-on. The ledger already carries `currency` on every account and posting, so the schema does not need to change.
**Build when** the single-currency ledger is proven and reconciled end to end.

### 3-D Secure and strong customer authentication
The cardholder challenge flow, with its redirect, its callback and its liability shift.
**Deferred because** it changes the shape of the authorization flow — introducing a suspended state awaiting a browser round trip — without exercising the routing or ledger problems that are the point of this project.
**Build when** a demonstration of a multi-step, resumable authorization flow is wanted.

### Network tokens and account updater
Scheme-issued tokens that survive card reissue, and automatic credential refresh.
**Deferred because** both depend on real scheme relationships and cannot be meaningfully simulated.

### Real interchange-plus pricing
Interchange, scheme fees, cross-border loading, tiered volume discounts.
**Deferred because** it is arithmetic and configuration, with no engineering signal beyond the rounding discipline already demonstrated by the flat basis-points-plus-fixed model.

### Executed payouts
An actual bank transfer, rather than a ledger movement and a statement.
**Deferred because** it requires a banking integration that cannot run locally.

---

## Platform

### Per-merchant fair scheduling
Weighted scheduling across per-merchant queues in the router, so one merchant's volume spike cannot starve another's traffic.
**Deferred because** the flagship problem in this project is acquirer routing, and adding a second scheduling problem would dilute it. The partitioning decision in [ADR-0005](adr/0005-kafka-partitioning.md) deliberately leaves room for it, precisely because it is a consumer-side scheduling concern rather than a partitioning one.
**Design sketch:** per-merchant sub-queues drained by weighted deficit round-robin, with Kafka partition pause and resume providing backpressure so lag stays durable in the broker rather than in heap.
**Build when** the routing story is complete and a second distributed-systems showcase is wanted. This is the strongest candidate on this list.

### Asynchronous re-presentation of unresolved authorizations
A timed-out authorization must be re-presented to the same acquirer with the same idempotency key, and today that happens inline on the Kafka consumer thread. A five-second acquirer deadline plus two re-presentations holds a consumer thread for fifteen seconds, and everything assigned to that thread waits.
**Deferred because** the blast radius is already bounded — six partitions and three listener threads mean one stalled payment delays a sixth of traffic rather than all of it, and re-presentation is capped at two attempts. Moving it off-thread means a delay topic and a scheduler, which is real machinery for a case that is rare by construction.
**Design sketch:** publish the unresolved attempt to a delayed-retry topic keyed by payment, consumed after a backoff, so the consumer thread returns immediately and per-payment ordering is preserved by the key.
**Build when** a load report shows consumer lag driven by unresolved authorizations rather than by throughput. This is the most likely first bottleneck under the Phase 4 brownout-under-load scenario.

### Shared acquirer health across router instances
Health is per process today: each instance forms its opinion from the traffic it actually sent, and instances converge independently ([ADR-0007](adr/0007-adaptive-routing.md)).
**Deferred because** the coordination would cost more than the disagreement it removes — every routing decision would depend on a round trip to a store holding a number that changes several times a second, and the thing being agreed on changes faster than agreement could be reached. The snapshot table already stops a restarting instance beginning blind.
**Build when** instance count is high enough that each one individually sees too little traffic to form an opinion — which is a real problem at low volume per instance, and not one this platform has.

### Change data capture instead of the polling outbox
Debezium reading the write-ahead log, removing the polling relay.
**Deferred because** it adds Debezium and Kafka Connect to a stack that must run on a laptop, and moves a correctness-critical component outside the services that depend on it. See [ADR-0004](adr/0004-transactional-outbox.md).
**Build when** publication latency or polling load appears as a measured bottleneck in a load report.

### Schema registry with Avro or Protobuf
Centrally governed, machine-enforced event schema evolution.
**Deferred because** versioned JSON envelopes with contract tests achieve compatibility discipline for a single-author system, and a registry is another stateful component in the local stack.
**Build when** more than one team owns producers.

### Distributed rate limiting for acquirer capacity
Router instances sharing a view of per-acquirer capacity rather than limiting independently.
**Deferred because** per-instance limits with a shared health model are sufficient at this scale, and a shared limiter needs a coordination store — a new dependency and a new failure mode.
**Build when** running enough router instances that per-instance capacity allocation becomes materially wasteful.

### A bandit-based router
Thompson sampling or a discounted upper-confidence-bound formulation replacing the EWMA plus exploration floor.
**Deferred because** the classical formulations assume stationary rewards and acquirer health is not stationary; the discounted variants converge on something close to what is already built, with more machinery. See [ADR-0007](adr/0007-adaptive-routing.md).
**Build when** real acquirer data allows the stationarity assumption to be tested.

### Machine-learned approval prediction
Predicting approval probability per acquirer from card, issuer, amount, time and history.
**Deferred because** it needs a data pipeline and training loop that would dwarf the rest of the project, and it depends on the deterministic control layer existing first — which is what was built.

### A specialised ledger engine
TigerBeetle or similar, with double-entry invariants enforced by the storage engine.
**Deferred because** PostgreSQL constraints enforce the same invariants at this scale, and a second unfamiliar datastore would spend a reader's attention on infrastructure rather than design. See [ADR-0008](adr/0008-double-entry-projection.md).

### The same modules deployed two ways
A `monolith` Spring profile booting `payment-api`, `router` and `ledger` in a single process from the same modules, alongside the normal four-process topology.
**Deferred because** it roughly doubles the integration-test surface — every behaviour would need verifying in both topologies, and a second topology that is not continuously tested is worse than no second topology. See [ADR-0014](adr/0014-service-boundaries.md).
**Build when** there is capacity to test both shapes continuously; it is the strongest possible evidence that the service split is genuinely reversible.

### Multi-region deployment
Active-active across regions, with data residency and cross-region consistency.
**Deferred because** it cannot be demonstrated locally and would be configuration theatre rather than working software.

### Service mesh
Istio or Linkerd for mutual TLS, traffic shifting and observability.
**Deferred because** the resilience patterns that matter here — breakers, retry budgets, health-aware routing — are *domain* logic in the router, not generic transport policy. A mesh would add a control plane to a laptop and obscure the very behaviour the project exists to show.

---

## Security

### A hosted identity provider
Keycloak, Auth0 or Cognito replacing self-issued JWTs.
**Deferred because** it would break the constraint that everything runs locally, and the engineering interest is the authorization model rather than token issuance. The claims contract is documented so the swap changes only validation configuration. See [ADR-0009](adr/0009-rbac-and-tenancy.md).

### Multi-factor authentication and password policy
**Deferred because** standard, well understood, and adds no signal.

### An external policy engine
Open Policy Agent or Cedar, with policy as testable data.
**Deferred because** the policy is a static role-to-permission matrix plus tenant scoping, which a table expresses more clearly than a policy language.
**Build when** per-resource or attribute-based rules are needed.

### Automated secret rotation
**Deferred because** there are no external credentials to rotate — a consequence of the local-first constraint.

---

## Product

### Merchant self-service onboarding
Signup, KYC, underwriting, risk scoring.
**Deferred because** merchants are seeded fixtures, and onboarding is a workflow problem rather than a distributed-systems one.

### Analytics and reporting beyond the portal
Cohort analysis, approval-rate breakdowns by issuer and bank identification number, revenue reporting.
**Deferred because** it is a data-warehouse project. The attempt-level data needed to support it is captured, which is the part that matters.

### A mobile application
**Deferred because** the portal covers the interface story.

---

## Ideas parked without a decision

Not yet evaluated. Recorded so they are not lost, and not promoted without the reasoning above.

- Smart capture timing — delaying capture to reduce refund rates on physical goods
- Authorization top-up and incremental authorization for hospitality and car rental
- A per-merchant sandbox mode routed to a dedicated simulated acquirer
- Retry scheduling for soft declines, exposed as an opt-in merchant dunning policy
- Cost-optimal routing under a hard success-rate floor, framed as a constrained optimisation
