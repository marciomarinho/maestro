# Architecture Decision Records

Every significant decision in Maestro is recorded here in [MADR](https://adr.github.io/madr/) form: the context that forced a choice, the decision, its consequences, and — the part that matters most — the alternatives that were rejected and why.

A record is **immutable once accepted**. A decision that changes is not edited; a new record supersedes it, and the old one is marked as such. The history of what was believed and when is itself part of the design documentation.

| # | Decision | Status |
|---|---|---|
| [0001](0001-monorepo-gradle-conventions.md) | Monorepo with Gradle convention plugins | Accepted |
| [0002](0002-java-25-spring-boot-4.md) | Java 25 and Spring Boot 4 | Accepted |
| [0003](0003-money-as-minor-units.md) | Money as integer minor units | Accepted |
| [0004](0004-transactional-outbox.md) | Transactional outbox with a polling relay, not CDC | Accepted |
| [0005](0005-kafka-partitioning.md) | Partition by payment, not by merchant | Accepted |
| [0006](0006-exactly-once-effects.md) | At-least-once delivery with exactly-once money effects | Accepted |
| [0007](0007-adaptive-routing.md) | Adaptive routing: EWMA health scoring with mandatory exploration | Accepted |
| [0008](0008-double-entry-projection.md) | A double-entry ledger as a projection, not event sourcing | Accepted |
| [0009](0009-rbac-and-tenancy.md) | Permission-based RBAC with layered tenant isolation | Accepted |
| [0010](0010-local-first-ephemeral-cloud.md) | Local-first, with ephemeral cloud as optional evidence | Accepted |
| [0011](0011-simulated-acquirers.md) | Simulated acquirers rather than real PSP sandboxes | Accepted |
| [0012](0012-never-retry-business-declines.md) | Never retry a business decline on another acquirer | Accepted |
| [0013](0013-idempotency-in-postgres.md) | Idempotency records in PostgreSQL, not a cache | Accepted |
| [0014](0014-service-boundaries.md) | Four deployables, and the test each split had to pass | Accepted |

## Template

New records follow [`template.md`](template.md).
