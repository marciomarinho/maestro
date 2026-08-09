# Adaptive routing

How Maestro decides which acquiring bank processes a payment, why it is built this way, and what it actually does when measured.

[ADR-0007](../adr/0007-adaptive-routing.md) records the decision. This document is the working explanation: the mechanism in detail, the numbers it produces, and the three things implementation taught that the decision record could not have known.

---

## The problem

Maestro holds relationships with several acquiring banks and must choose one for every transaction. The choice is commercial — acquirers differ in cost and approval rate — and operational, because acquirers degrade. Latency creeps up, technical declines spike, a region browns out for twenty minutes and recovers.

A static routing table cannot express this. It is written when an acquirer is healthy and keeps sending traffic while that acquirer is failing, because a configuration file does not know what is happening right now. Changing it needs a human to notice, decide and deploy — a loop measured in tens of minutes, during which the merchant is losing revenue.

Three properties are needed, and they are in tension:

1. **Exploit** — send traffic where it will most likely succeed, most cheaply.
2. **React** — move away from a degrading acquirer in seconds.
3. **Recover** — notice when a degraded acquirer is healthy again.

The third is where naive implementations fail, and the failure is invisible until it matters.

---

## The unit of decision is the corridor

Health, cost and capacity are properties of an acquirer combined with a card network and a currency — `southcross × VISA × AUD` — not of an acquirer as a whole. An acquirer can be healthy for domestic Visa traffic and failing for cross-border Mastercard, and a single per-acquirer number would average those into a figure describing neither.

Every structure in the router is keyed this way: `CorridorKey(acquirerId, corridor)`.

---

## Health scoring

Each corridor keeps three exponentially weighted moving averages, updated on every completed attempt.

| Signal | Denominator | What it answers |
|---|---|---|
| **Approval rate** | decisive outcomes only | of the times this acquirer answered, how often was it yes |
| **Technical failure rate** | all attempts | how often does this acquirer answer at all |
| **Latency** | answered attempts | how long an answer takes when one arrives |

The different denominators are deliberate. A timeout is not a decline, and folding it into approval rate would let an outage look like an issuer that has turned strict. Keeping them apart is what makes a brownout *legible* rather than merely visible: an acquirer whose approval rate is intact and whose failure rate has spiked is having an outage, while one whose failure rate is flat and whose approval rate has collapsed has had a risk rule changed — and no amount of failing over will help with the second.

A business decline never counts against availability. The acquirer did its job. A router that demoted acquirers for relaying declines would drift toward whichever bank happened to see the least risky traffic, which is a property of the merchant's customers rather than of the bank.

A capacity refusal counts against availability but is excluded from latency, because a refusal at the door returns in microseconds and would make a saturated acquirer look like the fastest on the panel.

### The EWMA, and why confidence is free

Every observation adds `x` to a weighted sum and `1` to a weight; both decay with the same half-life. The mean is `sum / weight`, and the weight **is** the effective sample count.

```
decay  = exp(−ln2 × Δt / halfLife)
sum    = sum × decay + x
weight = weight × decay + 1
mean   = sum / weight
```

That one mechanism gives both the average and the confidence in it, at the same decay rate — evidence that has aged out of the mean has aged out of the confidence in it too. No separate bookkeeping, and no way for the two to disagree.

Decay is by **elapsed time, not observation count**. A count-based window on a corridor receiving four requests a minute would still be reporting state from twenty minutes ago — and that is exactly the corridor where a stale opinion is most likely to be wrong and least likely to be corrected.

The half-life is the single responsiveness tunable. At **30 seconds**, a brownout registers while it is still happening and ordinary jitter does not move the number.

### Shrinkage toward a prior

A corridor with four samples has not earned a strong opinion, so the mean is shrunk toward a prior worth `priorWeight` imaginary observations:

```
reported = (sum + prior × priorWeight) / (weight + priorWeight)
```

With `priorWeight = 20`, a corridor with two real samples is mostly prior and one with two hundred is barely affected — with no threshold, no minimum-sample rule, and no special case for the transition. A single unlucky failure cannot evict a low-traffic corridor.

The priors are 0.90 approval and 0.02 technical failure: a plausible real acquirer rather than a perfect one, so a newly configured acquirer is neither shunned nor handed all the traffic.

---

## Selection

```
score = w_approval  × approval_rate
      + w_technical × (1 − technical_failure_rate)
      − w_latency   × normalised_latency
      − w_cost      × normalised_cost
```

| Weight | Value | Reasoning |
|---|---|---|
| `approval` | 1.00 | the commercial signal |
| `technical` | 1.20 | from a merchant's view a failure and a decline are the same event — a payment that did not happen |
| `latency` | 0.15 | it matters, but a fast decline is worth nothing; speed only breaks ties |
| `cost` | 0.40 | **availability outweighs cost roughly three to one** |

The cost weighting carries an argument worth having with a commercial stakeholder explicitly: an acquirer thirty basis points cheaper that fails one request in five is not cheaper. The lost margin on a declined sale dwarfs the saving on the ones that go through, and the declined customer may not come back.

### Latency and cost are compared, not measured

Approval and failure rates are absolute — 0.94 means the same thing everywhere. Latency and cost are not: 200 ms is excellent on one corridor and terrible on another. Both are min–max normalised **across the candidates available for this payment**, so the score answers "dearer or slower than the alternatives" rather than "dear or slow".

A corridor with no latency reading yet lands at the midpoint rather than at either end. Zero would make an unmeasured acquirer look like the fastest on the panel and hand it traffic it has not earned; one would make it look like the slowest and guarantee it never gets measured.

### Cost depends on the ticket size

Acquiring is priced as basis points plus a fixed fee, so **the cheapest acquirer is not the same acquirer at every amount**. On the demo's agreements:

| Acquirer | Rate | Fixed | Cost on $5.00 | Cost on $5,000 |
|---|---|---|---|---|
| southcross | 115 bps | 25c | 31c | $57.75 |
| northbank | 130 bps | 30c | 37c | $65.30 |
| meridian | 160 bps | 20c | **28c** | $80.20 |

Meridian is the cheapest option for a $5 payment and the dearest for a $5,000 one. A router comparing basis points alone would send every small ticket to the wrong bank and never show it in an average — so `select()` takes the amount, and cost is computed per payment.

### Softmax with a mandatory exploration floor

Scores become a distribution, and the router **draws** from it rather than picking the best:

```
p_i = floor + (1 − n × floor) × softmax(score_i / temperature)
```

Reserving `n × floor` before spreading the remainder by score makes the floor an exact guarantee rather than an emergent property that a sufficiently bad score could erode. At `floor = 0.05` and `temperature = 0.15`, a tenth of a point of score is worth roughly a doubling of share.

**This is the most important line of code in the platform.** A router that always picks the best score stops sending traffic to the alternatives, so it stops receiving evidence about them, so it can never learn that a demoted acquirer has recovered.

---

## Failover, breakers and the retry budget

### Two kinds of retry, which are opposites

| Outcome | What it means | What the router does |
|---|---|---|
| **Technical failure** | the acquirer's systems reported that nothing happened | **failover** — next-best acquirer, new attempt number, new idempotency key |
| **Timeout** | no answer at all; the payment's fate is unknown | **re-present to the same acquirer** with the same key |
| **Throttled** | refused at the door on capacity | failover; nothing was even attempted |
| **Business decline** | the issuer decided | nothing. Final, everywhere ([ADR-0012](../adr/0012-never-retry-business-declines.md)) |

The timeout rule is the subtle one. A timed-out authorization may well have succeeded at the acquirer, so asking a *different* bank is one of the few ways this platform could authorize the same payment twice. The acquirer that might already hold the authorization is the only party that can resolve the ambiguity, and the deterministic idempotency key `(payment_id, operation, attempt_no)` is what lets it.

### Circuit breakers, and where the probe comes from

Health scoring is proportional and gradual, which is right while the question is *how good* an acquirer is. The breaker answers a different question — *is it there at all* — and answers it absolutely. An acquirer refusing every connection should receive nothing, not five percent, because five percent of a large volume is a lot of customers and none of those requests can succeed.

Five consecutive unanswered calls opens the breaker and removes the corridor from selection entirely. After 15 seconds it half-opens, which **puts it back in the candidate set, where its ruined score earns it precisely the exploration floor**. There is no separate probing mechanism because none is needed: the floor *is* the probe. One answer closes the breaker; one failure reopens it without spending the threshold again.

### The retry budget

Retries are capped at **10% of request volume plus a small absolute floor**, measured with the same time-decayed counting the health model uses.

Without it, a total failure at one acquirer multiplies every payment on its corridors by the maximum attempt count, and hands that multiple to the acquirers now carrying all the real traffic — at the one moment they have least headroom. A single acquirer's outage becomes a platform-wide overload caused entirely by the platform's own recovery logic. The ratio follows Google's SRE practice and Finagle; the absolute floor exists because a pure ratio would refuse the first retry a quiet corridor ever asked for.

---

## What it actually does

Measured on the live stack — `./scripts/demo-brownout.sh`, three acquirers, real PostgreSQL and Kafka.

**Steady state.** Southcross, the cheapest and fastest, takes **~75–82%**. The other two split the rest as exploration. The deterministic simulation in `ScoringAcquirerSelectorTest` predicts this within a percentage point of the live stack, which is the main reason to trust the fast tests.

**Under brownout** (60% technical failures, latency ×6):

```
southcross  75%  →  24%          traffic moved
acceptance  120/120 (100%)       merchant-visible, during the brownout
```

**Recovery**, with nobody telling the router anything:

```
southcross  24%  →  45%          detected from exploration traffic alone
acceptance  60/60 (100%)
```

**The audit trail** for a payment that cascaded:

```
AUTHORIZE #1  southcross  EXPLORATION  score 1.8799  → DECLINED_TECHNICAL
AUTHORIZE #2  northbank   FAILOVER     score 1.7419  → APPROVED
CAPTURE   #1  northbank   PINNED       score n/a     → APPROVED
```

---

## Three things implementation taught

### 1. Pure argmax does not merely fail to detect recovery — it invents one

ADR-0007 argues that a router always picking the best score "can never learn that a demoted acquirer has recovered". Measured, the reality is worse.

Because health decays with elapsed time, a demoted acquirer's evidence does not freeze — it drains. After ten minutes of a totally broken acquirer receiving nothing:

| | with the 5% floor | pure argmax |
|---|---|---|
| effective samples | ~24–33 | **0.0** |
| reported failure rate | ~0.55 (the truth) | **0.02** (the prior) |

The argmax router ends up not ignorant but *confident and wrong*: it believes a completely broken acquirer is healthy, and will eventually route real payments back into it at full volume on the strength of having forgotten. The exploration floor buys the alternative for 5% of traffic — the reading stays true because it keeps being paid for.

This is asserted in `withoutExplorationTheRouterEndsUpConfidentAndWrong`. The decision in ADR-0007 is unchanged and still correct; only its reasoning was understated. ADRs here are immutable once accepted, so the correction lives in this document rather than as an edit to that one.

### 2. Demotion is self-damping, so it takes about a minute rather than a half-life

As an acquirer is demoted it receives less traffic, so it produces less of the evidence that would condemn it. The reading firms up more slowly than the half-life alone suggests — roughly 60 seconds to fall below 10% share, not 30.

This is a good property, not a defect: the system resists over-reacting to a brief wobble. It is also why the demotion bound in the tests is stated from measurement rather than derived from the half-life and assumed.

### 3. A brownout strands captures, and failover cannot help

Only authorization is routed. A capture goes to the institution holding the authorization, because a capture at a different bank would act against a reference that does not exist there.

So when an acquirer browns out, payments it had *already authorized* have their captures fail — 34 of them in one measured run, against 101 clean captures on the healthy acquirers. Those payments correctly stay `AUTHORIZED` with the authorization intact, retryable once the acquirer recovers.

The merchant-visible impact of a brownout is therefore not only about authorizations, and the [*acquirer brownout*](../operations/runbooks/acquirer-brownout.md) runbook says so.

---

## What is deliberately not here

**Capacity is not a column.** The corridor table holds only what somebody negotiated — cost and an enabled flag. Capacity is expressed where it actually bites: the acquirer refuses on capacity, and the router *learns* it as an availability signal. A column for something the router measures for itself would be a second source of truth that could disagree with the first.

**Health is per instance.** Instances converge independently. Sharing health would make every routing decision depend on a network round trip to a store holding a number that changes several times a second — the coordination would cost more than the disagreement it removes. Snapshots are persisted so a restarting router does not begin blind, capped at 10 samples so live traffic overturns a restored opinion within seconds.

**No manual override endpoint.** There is no button to force traffic to an acquirer or close a breaker by hand. The override that exists is the `enabled` flag on `acquirer_corridor` — a deliberate commercial act recorded in a table, not a button that resets when the process restarts.

---

## How this is tested

Two tiers, deliberately.

**Deterministic** — `ScoringAcquirerSelectorTest`, `HealthRegistryTest`, `ResilienceTest`. Injected clock, seeded die, no infrastructure. Every claim about time — detected in ten seconds, demoted within a minute, recovered within two — is an exact assertion that runs in milliseconds. Driving real traffic and sleeping would turn those into bounds so loose they proved nothing, and would be the flakiest thing in the repository.

**End to end** — `demo-brownout.sh`, executed by CI. Proves the whole thing is genuinely wired to real PostgreSQL, real Kafka and a real simulator, and that the numbers above are reproducible rather than modelled.

The fast tier proves the routing is *right*; the slow tier proves it is *connected*.

---

## Configuration

Everything tunable lives under `maestro.router` in `service/router/src/main/resources/application.yaml`, documented in `RouterProperties`. The shape of the router's judgement is a design decision and belongs under review; what an acquirer costs is a commercial agreement and lives in the `acquirer_corridor` table, where it changes without a deployment.

---

## Where this goes next

A discounted or sliding-window bandit — Thompson sampling over a non-stationary reward — is the natural evolution and is [in the backlog](../backlog.md). The chosen design is already a bandit in structure; it is made legible on purpose, because a design review can check this one and cannot easily check the other.
