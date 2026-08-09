# 0017. Expose the routing audit trail as a projection, not a cross-service read

- **Status:** Accepted
- **Date:** 2026-08-09

## Context

[ADR-0007](0007-adaptive-routing.md) promises that every routing decision is explainable after the fact, and [the API design](../architecture/api-design.md) places that explanation at `GET /v1/payments/{id}/attempts` — on payment-api, alongside every other merchant-facing endpoint.

The data is not there. Attempts are written by the router, into the router's schema, and payment-api's database role has no grant on it — deliberately, because [ADR-0010](0010-local-first-ephemeral-cloud.md) and [ADR-0014](0014-service-boundaries.md) make "no service reads another service's tables" fail at runtime rather than at code review.

So the promise and the ownership are in tension, and something has to bridge them.

This matters more than a typical read-path question because of *when* the endpoint is used. Nobody asks why a payment took the path it took while everything is working. They ask during an incident, or the morning after one, and the incident is usually the router having a bad time with an acquirer.

## Decision

The router publishes an **`payment.attempt_recorded`** event for every attempt, through the outbox it already owns. payment-api projects those events into `payment_attempt_view` in its own schema and serves the endpoint from there.

Three consequences of that shape are deliberate:

**Every attempt is published, not only the settling one.** A payment authorized on its second acquirer has a story, and the interesting half is the attempt that failed. Publishing only outcomes would show a payment approved at Northbank with no trace of the ninety seconds Southcross spent timing out first — which is precisely the question being asked.

**The event commits with the attempt row.** It is written inside the same transaction that records how the attempt ended, so the audit trail cannot lose an entry the router acted on.

**The projection is idempotent on `(payment_id, operation, attempt_no)`.** Delivery is at least once ([ADR-0006](0006-exactly-once-effects.md)), and a redelivery must overwrite rather than accumulate — otherwise one failover is explained to the merchant as three.

## Consequences

**Positive.** The merchant-facing read path is local, fast and available: it does not depend on the router being reachable, which matters because the router is the service most likely to be degraded during the incident someone is asking about. The service boundary stays enforced by database grants rather than by convention. The event stream gains a record of routing behaviour that Phase 4's dashboards and Phase 6's portal can both consume without either of them reaching into the router either.

**Negative.** The attempt history is duplicated, and a merchant reading immediately after a payment settles may briefly see a shorter history than the router holds. It is a real inconsistency window, in a service where nothing else has one, and it is acceptable only because this table holds no invariant — losing it entirely would leave the platform taking payments correctly and merely unable to explain them. There is also a new event type on a topic every service consumes, which every consumer must be willing to skip.

**Neutral.** The projection can be rebuilt by replaying the topic, so a schema change to the view does not need a migration that preserves data.

## Alternatives considered

### payment-api calls the router synchronously

An internal endpoint on the router, called on each read. No duplication, always current, and the simplest thing that could work.

Rejected because it makes a merchant-facing read path depend on the availability of the router. The failure is not hypothetical or even unlikely: it is *correlated*. The endpoint exists to explain acquirer incidents, so the moment of peak demand for it coincides exactly with the moment the router is least healthy — and a merchant investigating an outage would find the explanation endpoint timing out too. It would also put a synchronous inter-service hop on a path that has no other one.

### Serve the endpoint from the router

Put the endpoint where the data is, at `router:8081/v1/payments/{id}/attempts`.

Rejected because it breaks the merchant contract of a single base URL, and leaks the platform's internal topology to people who should never need to know it exists. It would also mean the router growing merchant authentication and tenant scoping, which is a second copy of security-critical code in a service that currently has none.

### Grant payment-api read access to the routing schema

One `GRANT SELECT`, and the problem disappears.

Rejected because it is the first exception to the rule that makes ADR-0014's boundaries real. The enforcement in this platform is that the grants do not exist; a boundary with one documented exception is a boundary maintained by memory, and the second exception is always easier to justify than the first.

### Have the router write into payment-api's schema

Rejected for the same reason, in the other direction, and additionally because it would make the router's transaction span two services' data.

## Revisit when

The inconsistency window becomes visible to merchants in a way that matters — a portal that shows an empty attempt list on a payment that has just settled — or the attempt volume makes duplication expensive enough to reconsider. Neither is likely before settlement data lands in Phase 5, which will pose the same ownership question again and should be answered the same way.
