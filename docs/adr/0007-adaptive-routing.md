# 0007. Adaptive routing: EWMA health scoring with mandatory exploration

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

This is the decision the project exists to demonstrate.

Maestro holds relationships with several acquiring banks. For each transaction it must choose one. The choice matters commercially — acquirers differ in cost and in approval rate — and it matters operationally, because acquirers degrade. Latency creeps up, technical declines spike, a region browns out for twenty minutes and recovers.

A static routing table cannot express this. It is written when an acquirer is healthy and keeps sending traffic while that acquirer is failing, because a configuration file does not know what is happening right now. Changing it requires a human to notice, decide and deploy — a loop measured in tens of minutes, during which the merchant is losing revenue.

Three properties are needed, and they are in tension:

1. **Exploit** — send traffic where it will most likely succeed, most cheaply.
2. **React** — move away from a degrading acquirer in seconds, not minutes.
3. **Recover** — notice when a degraded acquirer is healthy again.

The third is where naive implementations fail, and the failure is not obvious until it happens.

## Decision

Route by a **continuously updated health score per acquirer-corridor**, selected with a **mandatory exploration floor**, with cascading failover on technical failures and circuit breakers underneath.

### The unit of decision is the corridor

Health, cost and capacity are properties of an acquirer combined with a card network and a currency — `northbank × VISA × AUD` — not of an acquirer as a whole. An acquirer can be healthy for domestic Visa traffic and failing for cross-border Mastercard, and a single per-acquirer health number would average those into a figure that describes neither.

### Health scoring

Each corridor maintains exponentially weighted moving averages, updated on every attempt:

- **Approval rate** — approvals over decisive outcomes. The commercial signal.
- **Technical failure rate** — timeouts, connection errors, 5xx, malformed responses. The availability signal.
- **Latency** — EWMA of observed latency, normalised against the corridor's baseline.

An EWMA is chosen over a fixed window because it needs constant memory per corridor, weights recent evidence more heavily without a hard cutoff, and its responsiveness is a single tunable — the half-life, set at roughly thirty seconds so a brownout registers in seconds while ordinary jitter does not.

```
score = w_approval × approval_rate
      + w_technical × (1 − technical_failure_rate)
      − w_latency   × normalised_latency
      − w_cost      × normalised_cost
```

Weights are configuration, published in the design document with the reasoning behind their relative magnitudes. Availability dominates cost: an acquirer that is ten basis points cheaper and failing one request in five is not cheaper.

**Confidence matters.** A corridor with four samples has not earned a strong opinion. Scores are shrunk toward a neutral prior in proportion to sample count, so a single unlucky failure on a low-traffic corridor does not evict it.

### Selection with a mandatory exploration floor

Selection is a softmax over scores, with a guaranteed minimum share of traffic to every corridor that is not circuit-broken.

This is the part that naive implementations get wrong. **A router that always picks the highest score stops sending traffic to the alternatives, which means it stops receiving evidence about them, which means it can never learn that a demoted acquirer has recovered.** The system converges on whichever acquirer was healthiest at the moment of demotion and stays there — permanently, silently, and looking entirely correct.

The exploration floor makes recovery detectable and is the difference between a routing system and a routing table that updates itself once.

### Cascading failover — technical failures only

On a technical failure the payment is re-attempted on the next-best corridor, up to a bounded attempt count, with a fresh `attempt_no` and therefore a fresh acquirer idempotency key.

A **business decline is never re-attempted elsewhere**. This is a hard rule with its own record: [ADR-0012](0012-never-retry-business-declines.md).

### Circuit breakers and retry budget

A breaker per corridor opens on consecutive technical failures, excludes the corridor from selection, and probes periodically in half-open state. The exploration floor and the breaker interact deliberately: an open breaker suppresses exploration entirely, and half-open probing is what restores it.

A **retry budget** caps total retries as a fraction of request volume — around ten percent, following the approach used in Google's SRE practice and in Finagle. Without it, a total acquirer outage multiplies every request by the maximum attempt count and turns a single-acquirer failure into a platform-wide overload precisely when the remaining healthy acquirers can least absorb it. This is the failure mode that turns an incident into an outage.

### Explainability

Every attempt records the acquirer chosen, the reason (`BEST_SCORE`, `EXPLORATION`, `FAILOVER`, `PINNED`), and the score at the moment of selection. "Why did this payment go to Northbank?" is a query, not an investigation. This is exposed to merchants through `GET /v1/payments/{id}/attempts`.

## Consequences

**Positive.** The system adapts without configuration changes or human intervention. Degradation is detected in seconds. Recovery is detected automatically. Every decision is auditable after the fact. The behaviour is demonstrable live, which is the entire point of the project.

**Negative.** The exploration floor deliberately sends a small fraction of traffic to acquirers believed to be worse — a real, quantifiable cost paid for the ability to detect recovery, and one that must be stated to a commercial stakeholder rather than hidden. Weight tuning is empirical and could be fiddled with indefinitely; this is bounded by defining "done" as a set of tests rather than a feeling. Health state is per-router-instance, so instances converge independently rather than sharing a view.

**Neutral.** Health state is persisted so a restarting router does not begin blind, but it is advisory and rebuilt quickly from live traffic.

## Alternatives considered

### Static priority list with health-check failover

Configuration lists a primary and a secondary; failover on health-check failure. Rejected because a health check tells you whether the acquirer answers a synthetic probe, not whether it is approving real transactions. An acquirer returning `200 OK` to a health endpoint while declining eighty percent of live traffic passes every check — and this is exactly what a real brownout looks like.

### Pure argmax on the health score

Always choose the best. Rejected for the reason given above: without exploration it cannot detect recovery. This is the single most important insight in this record, and the recovery test in Phase 3 exists specifically to fail if anyone reintroduces it.

### A full multi-armed bandit — Thompson sampling or UCB1

Formally principled, with strong theoretical guarantees on regret. Genuinely attractive, and recorded in the backlog as the evolution path.

Rejected for now because the classical formulations assume stationary reward distributions, and acquirer health is emphatically non-stationary — a brownout is a distribution change, not an unlucky sample run. Handling that needs discounted or sliding-window variants, which reintroduce a decay parameter and end up close to the EWMA plus exploration floor already chosen, with more machinery and a harder story to tell in a design review. The chosen design is a bandit in structure, made legible.

### Weighted round-robin on fixed weights

Simple and predictable. Rejected because the weights are static and the problem is that conditions change.

### Machine-learned routing on historical outcomes

What large platforms eventually build, predicting approval probability from card, issuer, amount and time. Rejected as out of scope: it requires a data pipeline and a training loop that would dwarf the rest of the project, and it depends on having the deterministic control layer in place first — which is what this record specifies.

## Revisit when

Real (non-simulated) acquirer data is available and the stationarity assumption can be tested, or the exploration cost becomes commercially material enough to justify a discounted bandit.
