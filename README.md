# Maestro — Payment Orchestration Platform

**Maestro accepts payments from many merchants and routes each transaction to the best of several acquiring banks — automatically shifting traffic away from a degrading acquirer so merchants' authorization success rates stay flat — while a double-entry ledger and daily reconciliation guarantee every cent is accounted for.**

> **Status: Phase 1 complete — a payment goes end to end.** Create it, confirm it, watch
> it reach `AUTHORIZED` through the outbox, Kafka, the router and a simulated acquiring
> bank. Retry the same request and it is still charged exactly once.
> The design was settled first: see the [roadmap](docs/ROADMAP.md) and the
> [fourteen decision records](docs/adr/README.md).

Everything runs on a laptop — about 1.6 GB across five containers today. `docker compose up`
brings up the whole platform: services, Kafka, PostgreSQL, and simulated acquiring banks
you can break on purpose. The observability stack and the merchant portal join it in
later phases, behind their own profiles.

---

## The problem this solves

A merchant's checkout is only as reliable as the acquiring bank behind it. Acquirers degrade: latency creeps up, technical declines spike, an entire region browns out for twenty minutes. A payments platform that hard-codes one acquirer passes that failure straight through to the merchant as lost revenue.

Real orchestration platforms — Stripe, Adyen, Primer — solve this by holding relationships with several acquirers and deciding, per transaction, which one to use. That decision is the interesting part, and it is full of traps:

- If you always send traffic to the current best acquirer, you stop collecting data about the others and can never tell when they recover. **Exploration is mandatory.**
- If you retry a *business* decline ("insufficient funds", "stolen card") on a different acquirer, you are violating card-scheme rules and building a fraud vector. Only *technical* failures may be retried elsewhere. **The distinction between the two is the whole game.**
- If retries are unbounded, an acquirer brownout turns into a retry storm that takes down the acquirers that were still healthy.
- And underneath all of it, the money must still balance — through authorizations, captures, partial refunds, fees, settlement timing gaps and the occasional one-cent discrepancy in a bank's settlement file.

Maestro is built to demonstrate solutions to exactly these problems, with evidence rather than assertions.

---

## Architecture

```mermaid
graph TB
    subgraph clients[" "]
        M[Merchant server]
        P[Merchant portal<br/>React + TypeScript]
    end

    subgraph maestro["Maestro platform"]
        API[payment-api<br/>idempotency · outbox · authz · webhooks]
        RTR[router<br/>health scoring · failover · breakers]
        LDG[ledger<br/>double-entry · settlement · reconciliation]
    end

    subgraph sim["Simulated environment"]
        ACQ[acquirer-sim<br/>configurable declines · latency<br/>brownouts · settlement files]
    end

    K[(Kafka)]
    DB[(PostgreSQL)]

    M -->|REST + API key| API
    P -->|REST + JWT / SSE| API
    API -->|outbox relay| K
    K --> RTR
    RTR -->|authorize · capture · refund| ACQ
    RTR -->|outcomes| K
    K --> LDG
    K --> API
    ACQ -.->|daily settlement files| LDG
    API --- DB
    RTR --- DB
    LDG --- DB
```

Four JVM services and a web portal, each with one job. Full detail — component responsibilities, the payment state machine, event flows and sequence diagrams — is in [`docs/architecture/overview.md`](docs/architecture/overview.md).

---

## What this project demonstrates

| | |
|---|---|
| **Distributed systems** | Transactional outbox, at-least-once delivery with exactly-once *money effects*, per-payment ordering, idempotency at every boundary |
| **The flagship problem** | Adaptive multi-acquirer routing: EWMA health scoring per corridor, cost weighting, mandatory exploration, cascading failover, circuit breakers, retry budgets |
| **Financial correctness** | Double-entry ledger with database-enforced balance invariants, append-only postings, authorization holds modelled separately from movements, a concurrent double-capture race test |
| **Reconciliation** | Settlement-file ingestion, line-level matching, discrepancy classification, drift metrics — the unglamorous work that real payments teams live on |
| **Security** | Four-role RBAC on permissions (not roles), per-merchant scoping with Postgres row-level security as defence in depth, `404`-not-`403` to avoid leaking existence, card data never entering the system |
| **Operations** | End-to-end tracing of a single payment, dashboards as code, chaos experiments, runbooks written before they are needed |
| **Full-stack range** | A polished merchant portal with a live payment feed and RBAC-gated money actions |
| **Engineering judgement** | Fourteen decision records that say what was rejected and why, and a written backlog of everything deliberately not built |

---

## Roadmap

- [x] **Phase 0** — Design foundation: architecture, domain, API, authorization model, 14 ADRs
- [x] **Phase 1** — Walking skeleton: create → confirm → authorize end-to-end, running locally
- [ ] **Phase 2** — The books: double-entry ledger, holds, capture/void/refund, race tests
- [ ] **Phase 3** — The flagship: adaptive routing, failover, circuit breakers, the brownout demo
- [ ] **Phase 4** — The evidence: tracing, dashboards, load reports, chaos experiments
- [ ] **Phase 5** — Settlement & reconciliation: files, matching, discrepancies, payouts, webhooks
- [ ] **Phase 6** — RBAC & merchant portal
- [ ] **Phase 7** — Kubernetes on a local cluster, CI polish, demo recording
- [ ] **Phase 8** — *(optional)* Ephemeral AWS deployment via Terraform

Each phase leaves the repository in a finished, demoable state. Details and definitions of done: [`docs/ROADMAP.md`](docs/ROADMAP.md).

### What works today

A payment is created and confirmed in a single database transaction that also claims the
idempotency key and appends the authorization command to the outbox — three writes, one
commit, so no crash can leave them disagreeing. A relay publishes the command to Kafka,
claiming rows with a per-aggregate advisory lock so concurrent instances cannot reorder
one payment's events. The router claims a numbered attempt, calls the acquirer with an
idempotency key derived from it, and publishes the outcome through its own outbox. The
API applies that outcome with a guarded conditional update, which is what makes a
redelivered event a no-op instead of a second charge.

Tests that hold those claims up: five ArchUnit rules (the domain library stays
framework-free, no floating-point money, no cross-service dependencies) and seven
integration tests against real PostgreSQL and Kafka — including twelve concurrent
identical requests producing exactly one payment, and another merchant's payment
returning `404` rather than `403`.

---

## Documentation

| Document | What it covers |
|---|---|
| [Vision](docs/VISION.md) | Why this exists, who it is for, what "done" means |
| [Roadmap](docs/ROADMAP.md) | The eight phases, deliverables, demo criteria, definitions of done |
| [Architecture overview](docs/architecture/overview.md) | C4 diagrams, services, state machine, event flows, data ownership |
| [Domain model](docs/domain.md) | Payments vocabulary, entities, money-handling rules, invariants |
| [API design](docs/architecture/api-design.md) | REST surface, idempotency, errors, pagination, webhooks |
| [Authorization model](docs/security/authz-model.md) | Roles, permissions, tenant isolation, PCI scope boundary |
| [Decision records](docs/adr/README.md) | Fourteen ADRs — the trade-offs, including the rejected options |
| [Operations](docs/operations/README.md) | Runbooks and the incident-response posture |
| [Backlog](docs/backlog.md) | Consciously deferred work, with the reasoning |

---

## Quickstart

Requires Docker and `jq`. Nothing else — no JDK, no account, no credentials. A scheduled
CI job runs exactly these commands, so they cannot silently stop working.

```bash
git clone <this repo> && cd maestro
docker compose -f deploy/compose/docker-compose.yml up -d --wait
./scripts/demo-first-payment.sh
```

The script takes a payment end to end and then proves four things: the same request
replayed under the same idempotency key returns the original payment rather than creating
a second one; the same key with a *different* body is rejected with `409` rather than
silently succeeding; and a request without a credential is rejected with `401`.

Or by hand:

```bash
curl -X POST localhost:8080/v1/payments \
  -H 'Authorization: Bearer sk_test_maestro_demo_0001' \
  -H 'Idempotency-Key: demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"amount_minor": 1999, "currency": "AUD", "card_token": "tok_visa_4242", "confirm": true}'

curl localhost:8080/v1/payments/<id> -H 'Authorization: Bearer sk_test_maestro_demo_0001'
```

If ports 8080–8082 are already taken on your machine, copy
`deploy/compose/.env.example` to `deploy/compose/.env` and change them; the scripts follow.

**Working on it locally** — Java 25 and Docker:

```bash
sdk env install && sdk env      # Temurin 25, pinned in .sdkmanrc
./gradlew build                 # unit + architecture tests
./gradlew integrationTest       # real PostgreSQL and Kafka via Testcontainers
```

---

## Licence

MIT — see [LICENSE](LICENSE).
