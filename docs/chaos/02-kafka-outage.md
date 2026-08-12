# Experiment 02 — The broker disappears

**Fault:** Kafka unreachable by every service (the `kafka` proxy disabled — connections
severed, new ones refused).
**Run:** 2026-08-12, `scripts/chaos/kafka-outage.sh`, 20 payments during the outage.
**Verdict: hypothesis held.**

## Hypothesis

Payment creation keeps succeeding with the broker down. The 202 a merchant receives
depends on one PostgreSQL transaction — state change plus outbox row — and on nothing
else (ADR-0004); publishing is somebody else's job that happens later. Commands will
queue as unpublished outbox rows, the affected payments will hold in `AUTHORIZING`,
nothing will be lost, and when the broker returns the backlog drains and completes
without any operator action.

## Method

1. Baseline: one payment driven to `CAPTURED`.
2. Disable the `kafka` proxy — this severs established connections, not just new ones.
3. Create 20 payments; assert every one returns 202.
4. Assert the queue is visible: `maestro_outbox_pending` ≥ 20, statuses `AUTHORIZING`.
5. Re-enable the proxy; await all 20 reaching `CAPTURED`.

## Observation

- **20/20 accepted with the broker down.** The merchant surface never noticed.
- The outbox held exactly the backlog: `pending=20`, oldest row seconds old, every
  payment parked in `AUTHORIZING` — the honest state, since no acquirer had answered.
- On reconnection the backlog **drained in 5 seconds**, and all 20 payments reached
  `CAPTURED` with no intervention and no duplicates.

## What this says about the design

This is the transactional outbox doing the one thing it exists to do, and the reason
"relay publishes, then marks" is the right order: the crash-and-outage story is
*duplicates never losses*, and consumer idempotency (ADR-0006) absorbs the duplicates.

Two operational notes came out of it:

- The queue is only visible because the gauges exist. `maestro_outbox_pending` rising
  while `maestro_outbox_oldest_age_seconds` rises with it is a broker problem;
  pending *flat* while age rises is a stalled relay — different runbooks, one pair of
  gauges ([outbox relay stalled](../operations/runbooks/outbox-relay-stalled.md)).
- The producer's own delivery timeout (2 minutes, Kafka default) bounds how long a
  relay pass can hang on a send before the poll cycle retries it. An outage longer
  than the demo's would surface as relay passes failing loudly in the logs while rows
  wait — which is the designed behaviour, not an incident in itself.
