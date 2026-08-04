# 0011. Simulated acquirers rather than real PSP sandboxes

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

The central claim of this project is that the router keeps merchants' success rates stable while acquirers degrade. That claim is only worth making if it can be *shown*, on demand, in a way an observer can trigger themselves.

Showing it requires an acquirer that fails on command: raise the technical failure rate to forty percent, add three hundred milliseconds of latency, cap throughput, black out entirely for ninety seconds, then recover.

Real payment sandboxes — Stripe's test mode, Adyen's test environment — cannot do this. They are built to be reliable. They offer magic card numbers that produce specific declines, which covers business declines but not the failure modes that matter here: latency distributions, timeouts, throttling, partial brownouts, and recovery. They also require accounts and credentials, which violates the constraint that everything runs locally ([ADR-0010](0010-local-first-ephemeral-cloud.md)), and they impose rate limits that make load testing impossible.

## Decision

Build `acquirer-sim`: a service hosting several simulated acquiring banks, with a runtime fault-injection API. It is treated as a first-class component with the same engineering standards as the rest of the platform, not as a test fixture.

**Realistic behaviour.** Scheme-style response codes, an authorization/capture/refund/void lifecycle, per-corridor cost and capacity, acquirer references, and end-of-day settlement files including a fee column.

**Controllable failure**, per acquirer and per corridor, changeable at runtime without a restart: approval and decline rates by code, latency drawn from a configurable distribution, timeout rate, throughput cap producing `429`s, brownout (degraded but responding) and blackout (not responding at all).

**Honours idempotency keys.** A retried attempt carrying the same key returns the original outcome instead of authorizing again. This is what makes the router's retry logic testable rather than merely written — without it, a duplicate authorization would be invisible in the simulator.

**Deterministic when seeded**, so a demo produces the same shape every time and a failing test is reproducible.

**Injectable settlement discrepancies** — a one-cent difference on a nominated line, a duplicated row, a missing transaction — which is what makes the reconciliation engine demonstrable rather than a paragraph of prose.

## Consequences

**Positive.** Every resilience claim becomes a live demonstration an observer can trigger. Load tests run at any volume with no rate limit and no cost. Chaos experiments are reproducible. The reconciliation story is testable, which it could not be against a sandbox that never produces a discrepancy. There are no credentials to manage or leak, and nothing to expire.

**Negative.** A simulator is not reality. It cannot reproduce the specific weirdness of a real acquirer — undocumented response codes, connection pool quirks, the difference between what an integration guide says and what the endpoint does. Anyone reading this project should understand that the routing logic is validated against a model of acquirer behaviour, not against acquirers. This is stated in the README rather than glossed over, and it is the honest position: the *design* is what is being demonstrated.

There is also an obvious trap — writing a simulator that fails in exactly the ways the router handles well. Mitigated by defining the simulator's fault modes first, from documented real-world acquirer behaviour, before the router logic that responds to them.

**Neutral.** The simulator is roughly the same size as a real acquirer integration would be, so it is not a shortcut in effort — only in dependencies.

## Alternatives considered

### Real PSP sandboxes

Rejected above: they cannot fail on command, they need credentials, and they rate-limit.

### A mock library such as WireMock, inside the tests

Adequate for unit and integration testing and much less work. Rejected because it exists only inside the test process. There would be nothing to run in a demo, nothing to load test against, and nothing for an observer to break themselves — which is the specific thing that makes this project convincing rather than merely well described.

### A simple always-approve stub

Enough for a walking skeleton, and it is in fact what Phase 1 ships. Rejected as an end state, since it would make the flagship phase undemonstrable.

### Recorded traffic replayed from a real acquirer

The most realistic option. Rejected: no such traffic is available, and even with it, replay cannot respond to a *new* request, which is what routing decisions require.

## Revisit when

A real acquirer sandbox becomes available for a supplementary integration test — the interface is already abstracted, so adding one alongside the simulator would be additive rather than a replacement.
