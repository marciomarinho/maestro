# Experiment 03 — The acquirer network hangs, for everyone

**Fault:** the router's path to every acquirer stops answering — connections open,
then silence (Toxiproxy `timeout` toxic, holding connections forever).
**Run:** 2026-08-12, `scripts/chaos/acquirer-timeouts.sh`, 10 payments into the fault.
**Verdict: hypothesis held**, more tightly bounded than predicted.

## Hypothesis

This is the failure failover cannot fix — there is nowhere to fail over to — so what
is under test is restraint. Predicted: payments are still accepted (202); each reaches
a **terminal** `FAILED` after a bounded number of attempts rather than hanging in
`AUTHORIZING`; the retry budget keeps total attempts well under the unbounded worst
case (~6 per payment); breakers open; and once the network heals, the next payment
authorizes with no operator action, because the exploration floor is the probe.

## Method

1. Baseline: one payment driven to `CAPTURED`.
2. Inject the `timeout` toxic on the `acquirers` proxy (never answer; the router's 5 s
   request timeout is what ends each attempt).
3. Create 10 payments; assert 202s; await terminal states.
4. Count settled attempts from `maestro_router_attempts_total`; read the retry budget
   and breaker states.
5. Heal; assert the very next payment completes unaided.

## Observation

- **10/10 accepted** — the merchant surface does not block on acquirers.
- **Every payment reached `FAILED`; none stuck.** The audit trail for each shows the
  attempt(s) and the timeout outcomes (ADR-0017).
- **10 settled attempts for 10 payments** — not the ~60 of the unbounded worst case,
  and below even a cautious prediction. Two mechanisms compounded: the first payments'
  timeouts opened the per-corridor breakers (five consecutive failures each), which
  removed those corridors from selection for the payments behind them; and the retry
  budget (utilisation observed at 0.27) refused what failover the breakers still
  allowed. Later payments spent one attempt each and were honestly refused.
- **Recovery needed nobody.** With the toxic removed, the first new payment was
  captured: half-open breakers readmitted corridors, the exploration floor delivered
  the probe traffic, and the corridor closed its own breakers.

## What this says about the design

The bound held because *three* independent brakes exist — per-payment attempt caps,
breakers, and the platform-wide budget — and this experiment is the case where they
overlap. Remove any one and the others still prevent the storm; that redundancy is the
point (ADR-0007, ADR-0012, `docs/architecture/routing.md`).

One honest caveat: payments that were **mid-flight when the hang began** each consumed
a full 5 s timeout before failing over into open breakers — a total outage converts
in-flight work into worst-case latency before it converts into fast failure. The
merchant-visible effect is a burst of slow `FAILED` payments at the start of the
outage, then quick ones. That shape is visible on the routing dashboard's latency
panel and is the expected signature of this fault.
