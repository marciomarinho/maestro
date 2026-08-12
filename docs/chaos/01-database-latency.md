# Experiment 01 — Database latency on the merchant-facing path

**Fault:** every packet from PostgreSQL to payment-api delayed 200 ms ± 50 ms
(Toxiproxy `latency` toxic, downstream).
**Run:** 2026-08-12, `scripts/chaos/db-latency.sh`, 30 payments per phase at demo scale.
**Verdict: hypothesis held**, with a multiplier worth knowing about.

## Hypothesis

Payment creation latency will rise by *several multiples* of the injected 200 ms —
a creation is several database round trips inside one transaction, so per-packet
latency compounds — but nothing will fail: every request still returns 202, the
outbox keeps draining, nothing is lost, and latency returns to baseline the moment
the fault is removed.

## Method

1. Baseline: 30 payment creations, latency summarised from the client.
2. Inject the toxic on the `postgres-payment` proxy; 30 more creations, every one
   asserted to return 202.
3. Read the outbox gauges (the relay shares the slowed connection).
4. Remove the toxic; 10 more creations; assert the median returns to baseline.

## Observation

| Phase | median | p90 | max |
|---|---|---|---|
| Baseline | 5 ms | 5 ms | 13 ms |
| +200 ms injected | 1 225 ms | 1 325 ms | 1 425 ms |
| Healed | 5 ms | 6 ms | 9 ms |

- **The multiplier is ~6.** A single confirmed creation costs about six database round
  trips (idempotency claim, merchant lookup, insert, outbox append, idempotency
  completion, commit — the flow in `PaymentService.create`). 200 ms of network became
  1.2 s of checkout.
- **Zero failures.** 30/30 accepted under fault; the outbox stayed effectively empty
  (pending 1, oldest 1 s) because the relay, slow or not, kept pace with demo-scale
  arrival.
- **Recovery was immediate** — the next batch after healing sat exactly on baseline.

## What this says about the design

The p99 < 150 ms SLO dies at roughly +25 ms of database latency, long before anything
*fails* — so the [database saturation runbook](../operations/runbooks/database-saturation.md)
is right to treat statement latency as the leading signal rather than errors.

The quieter risk is arithmetic: at 1.2 s per creation, 16 concurrent checkouts exhaust
payment-api's connection pool (16). Demo-scale traffic left headroom; production-scale
would not. `hikaricp_connections_pending` above zero is the tripwire, and it is on the
golden-signals dashboard.
