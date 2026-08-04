# Roadmap

Eight phases. Each one ends with the repository in a finished, demoable state: `main` green, the quickstart accurate, documentation current, nothing scaffolded that is not yet built.

**The golden rule.** An unchecked roadmap box reads as deliberate planning. An empty `ledger/` directory with a stub class reads as abandonment. No component is created before its phase begins.

**Definition of done, applied to every phase.** A phase is complete when: the code is merged to `main` with CI green; the tests named in the phase pass; the demo script runs end to end from a cold `docker compose up`; the documents listed for the phase exist and are accurate; and the README roadmap box is ticked.

---

## Phase 0 — Design foundation ✅

**Deliverable:** the entire design, written down, before any code.

| Document | Purpose |
|---|---|
| `README.md` | The thirty-second pitch |
| `docs/VISION.md` | Why this exists, what done means, constraints and non-goals |
| `docs/ROADMAP.md` | This document |
| `docs/architecture/overview.md` | C4 diagrams, service responsibilities, state machine, event flows |
| `docs/domain.md` | Vocabulary, entities, money-handling rules, invariants |
| `docs/architecture/api-design.md` | REST surface, idempotency semantics, errors, webhooks |
| `docs/security/authz-model.md` | Roles, permissions, tenant isolation, PCI scope |
| `docs/adr/0001`–`0014` | The fourteen decisions, with rejected alternatives |
| `docs/operations/README.md` | Runbook template and operational posture |
| `docs/backlog.md` | Everything deliberately not built |

**Done when:** the repository tells the complete story with zero lines of code, and is reviewable on its own.

---

## Phase 1 — Walking skeleton

The thinnest possible slice that touches every layer. Nothing clever; everything connected.

**Build**
- Gradle multi-module monorepo: convention plugins, version catalog, `.sdkmanrc` pinning Temurin JDK 25
- `payment-api`: `POST /v1/payments`, `POST /v1/payments/{id}/confirm`, `GET /v1/payments/{id}` — with idempotency keys and the transactional outbox
- Merchant identity from day one: API-key authentication and merchant scoping (the full role model arrives in Phase 6, but nothing is ever unauthenticated)
- Outbox relay publishing to Kafka
- `router`: consumes authorization commands, calls a single acquirer, publishes the outcome
- `acquirer-sim`: one acquirer, fixed latency, always approves
- `deploy/compose/docker-compose.yml`: PostgreSQL, Kafka (KRaft), the three services
- CI: build and test on every push

**Demoable:** `./scripts/demo-first-payment.sh` creates a payment, confirms it, and shows it reach `AUTHORIZED`. Re-sending the confirmation with the same idempotency key returns the identical response and produces no second authorization.

**Done when**
- `./gradlew build` passes, including the first ArchUnit rules (module dependency directions, no Spring in the domain library)
- An integration test on real PostgreSQL and Kafka drives a payment from creation to `AUTHORIZED`
- The quickstart in the README works from a cold clone
- ADRs 0001–0006 are accurate against the code that now exists

---

## Phase 2 — The books

Money becomes real. This phase is about correctness under concurrency, not features.

**Build**
- `ledger` service: chart of accounts, journal transactions, append-only postings
- Database-enforced invariants: every transaction balances to zero; postings are insert-only; a transaction is idempotent on its source event identifier
- Authorization **holds** modelled separately from movements — an authorization reserves, it does not move money ([ADR-0008](adr/0008-double-entry-projection.md))
- Full payment lifecycle: capture (full and partial), void, refund (full and partial), expiry of stale authorizations
- Fee calculation at capture
- Balance verification job: recompute balances from postings and compare with the materialised view

**Demoable:** a payment is authorized, captured and partially refunded; the ledger view shows every posting, balanced, with the merchant payable and platform fee accounts moving correctly.

**Done when**
- The **double-capture race test** passes: N concurrent capture requests on the same payment against real PostgreSQL produce exactly one capture and one set of postings
- An attempt to insert an unbalanced transaction is rejected by the database, asserted in a test
- The balance verification job reports zero drift across a randomised transaction workload
- Runbook: *ledger drift detected*

---

## Phase 3 — The flagship

The reason the project exists. Everything before this was table stakes.

**Build**
- Multiple acquirers, each with per-corridor (card network × currency) configuration for cost, capacity and behaviour
- Health scoring: EWMA of approval rate, technical-failure rate and latency per acquirer-corridor
- Selection: cost-weighted scoring with a mandatory **exploration floor** so a demoted acquirer still receives enough traffic for its recovery to be detected ([ADR-0007](adr/0007-adaptive-routing.md))
- **Cascading failover** on technical failures only — never on a business decline ([ADR-0012](adr/0012-never-retry-business-declines.md))
- Circuit breaker per acquirer-corridor with half-open probing
- Retry with exponential backoff and full jitter, governed by a **retry budget** capping retries as a fraction of request volume, so a brownout cannot become a retry storm
- Deterministic acquirer-facing idempotency keys derived from `(payment_id, attempt_no)`
- `acquirer-sim` gains its fault-injection API: decline rates, latency distributions, timeouts, throughput caps, brownout and blackout modes
- Design document: `docs/architecture/routing.md` — the centrepiece technical write-up

**Demoable:** `./scripts/demo-brownout.sh`. Steady traffic across two acquirers. Acquirer A is degraded live. The dashboard shows its health score collapse, traffic shift to acquirer B, failed authorizations cascade successfully, the breaker open and later half-open — and the merchant-visible success rate barely move. A is healed; exploration traffic detects it; the split returns to normal.

**Done when**
- **Traffic-shift test:** after an acquirer's technical failure rate is raised, its share of traffic drops below a threshold within a bounded time
- **Success-rate floor test:** end-to-end approval rate during a single-acquirer brownout stays above a stated floor
- **Recovery test:** a healed acquirer regains traffic — proving the exploration floor works, which a pure argmax router would fail
- **No-retry-on-decline test:** a business decline is never re-attempted on another acquirer
- **Retry-budget test:** under a total-outage scenario, attempts per second stay bounded
- Runbooks: *acquirer brownout*, *circuit breaker stuck open*

---

## Phase 4 — The evidence

Claims become numbers. This phase produces the artefacts an interviewer reads after they are already interested.

**Build**
- OpenTelemetry tracing end to end: HTTP request → outbox → Kafka headers → router → acquirer → ledger posting, correlated by payment
- Grafana dashboards committed as provisioned JSON, not hand-built: payments funnel, acquirer health and routing split, ledger integrity, service golden signals
- k6 load scenarios: steady state, spike, brownout-under-load
- A published load report with an honest narrative: the bottleneck that was found, what was changed, before and after numbers
- Chaos experiments using Toxiproxy: database latency, Kafka partition unavailability, acquirer timeouts — each with a written hypothesis and observed result
- Runbooks: *consumer lag*, *dead-letter queue growth*, *database saturation*

**Demoable:** follow a single payment's trace across all four services in Tempo; open a dashboard showing the routing split live.

**Done when:** `docs/load-reports/` contains at least one dated report with graphs and analysis; `docs/chaos/` contains at least three experiments with results; every dashboard is reproducible from a cold start with no manual clicking.

---

## Phase 5 — Settlement and reconciliation

The unglamorous work that separates people who have run payments systems from people who have read about them.

**Build**
- `acquirer-sim` generates end-of-day settlement files per acquirer, with realistic timing gaps, fee lines, and an injectable discrepancy mode
- Settlement ingestion: parse, validate, stage
- Line-level matching against the ledger, with discrepancy classification: matched, missing in ledger, missing in file, amount mismatch, fee mismatch, duplicate, timing difference
- Reconciliation runs with drift metrics and an exceptions queue
- Settlement postings: receivable → cash; merchant payout statements
- Merchant webhooks: HMAC-signed, timestamped, replay-protected, with retry and a delivery log

**Demoable:** `./scripts/demo-reconciliation.sh` — instruct the simulator to emit a settlement file with a one-cent discrepancy on a single line; the reconciliation run flags exactly that line, classifies it, and raises the drift metric.

**Done when**
- Reconciliation over a full simulated day of traffic reports zero unexplained discrepancies
- Each injected discrepancy type is detected and correctly classified, asserted by tests
- Webhook signatures verify against an independent implementation in the test suite
- Runbook: *reconciliation discrepancy triage*

---

## Phase 6 — RBAC and the merchant portal

**Build**
- Full authorization model: four roles (`merchant_admin`, `merchant_developer`, `platform_ops`, `auditor`), permission-based enforcement, self-issued JWTs with a JWKS endpoint
- Tenant scoping enforced centrally, with PostgreSQL row-level security as defence in depth
- Audit log for every privileged action
- Merchant portal (React + TypeScript + Vite): live payment feed over SSE, payment detail with its attempt history and ledger postings, acquirer health and routing visualisation, reconciliation exceptions, balances and payouts
- RBAC-gated write actions: issue a refund, manage API keys, approve a reconciliation exception
- Playwright end-to-end tests covering the role differences

**Demoable:** log in as `merchant_admin` and as `auditor` side by side and watch the available actions differ; run the brownout demo and watch the routing chart move in the portal in real time; issue a refund from the UI and see its ledger postings appear.

**Done when**
- **Tenant-isolation test:** parameterised across every merchant-scoped endpoint, a token for merchant A receives `404` — not `403` — for merchant B's resources
- **ArchUnit rule:** every controller method carries an explicit authorization annotation; there is no default-permit path
- Row-level security is proven by a test that bypasses the application layer and queries the database directly with the application role
- Playwright suite passes in CI against the composed stack

---

## Phase 7 — Ship and shine

**Build**
- Kubernetes manifests (Kustomize base plus a `kind` overlay), probes, resource limits, horizontal autoscaling on a queue-depth signal
- `make kind-up` — a local cluster running the entire platform
- CI publishing container images; a scheduled smoke workflow that executes the README quickstart so it cannot rot
- Demo recording (GIF or asciinema) of the brownout scenario, embedded at the top of the README
- Final documentation pass: diagrams current, ADR index complete, every link live

**Done when:** a cold `make kind-up` followed by `./scripts/smoke.sh` passes against the local cluster; all badges are green; the README passes the thirty-second test with a fresh reader.

---

## Phase 8 — Ephemeral cloud *(optional)*

Additional evidence, never a dependency. The platform remains fully functional with this phase absent.

**Build**
- Terraform modules: VPC, EKS, RDS PostgreSQL, ECR, IAM least-privilege roles, remote state with locking
- The environment is designed to be created, exercised and destroyed: `terraform apply` → smoke and load tests → capture evidence → `terraform destroy`
- CI runs `terraform fmt`, `validate` and `plan` on every push so the configuration cannot rot
- A documented cost breakdown per cycle

**Done when:** a recorded full cycle is published in `docs/cloud/` with screenshots, test output and the actual cost.

---

## Sequencing rules

1. **Documents ship with their phase.** No end-of-project documentation sprint; a decision record lands in the same commit range as the code it explains.
2. **New ideas go to [the backlog](backlog.md), not the build.** Scope is a contract with the calendar.
3. **Done is defined by tests, not by feel.** Phase 3 in particular could be tuned indefinitely; it is finished when its five named tests pass.
4. **Trunk is always green and the demo always runs.** A scheduled CI job executes the quickstart, so the documentation cannot silently drift from reality.
