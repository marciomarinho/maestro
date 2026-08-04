# 0010. Local-first, with ephemeral cloud as optional evidence

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

A portfolio project has an audience problem that a production system does not. Its value depends on someone unfamiliar with it being able to run it, break it and believe it — within minutes, without an account, a credential, a credit card or a support conversation.

It also has a lifespan problem. Hosted demo environments rot. A domain lapses, a free tier ends, a managed service deprecates a version, and eighteen months later the most prominent link in the README returns a certificate error. The project is then worse than if the link had never existed, because a dead demo reads as abandonment.

Against that, infrastructure-as-code and cloud deployment are genuine hiring signals, and their absence is noticeable for a platform-level role.

## Decision

**Everything runs locally. The cloud is optional evidence, never a dependency.**

The whole platform — four services, Kafka, PostgreSQL, the observability stack, the portal, every test, every demo — runs on a laptop via Docker Compose, and later on a local `kind` Kubernetes cluster. **No feature, test or demo may require a cloud account, an external credential, or a network call to a third party.** This is a hard constraint, not a preference, and it is the reason acquiring banks are simulated ([ADR-0011](0011-simulated-acquirers.md)).

Consequences of the constraint that shape other decisions: identity is self-issued rather than delegated to a hosted provider; there is no schema registry, no managed queue, and no external secret store; the observability stack is self-hosted and sits behind a Compose profile so the everyday loop stays light on a laptop.

**One PostgreSQL instance with a schema per service**, not four database containers. The boundary that matters is logical — no service reads another's schema — and it is enforced by separate database roles with grants only on their own schema, so a violation fails at runtime. Four containers would enforce the same rule at four times the memory cost, on a machine that is also running Kafka, a browser and an IDE.

**Terraform and AWS are Phase 8, and Phase 8 is optional.** The environment is designed to be created, exercised and destroyed: `terraform apply`, run the smoke and load tests, capture the evidence, `terraform destroy`. What is published is the recording, the test output, the screenshots and the actual cost — artefacts that do not expire. Continuous integration runs `terraform fmt`, `validate` and `plan` on every push, so the configuration is verified continuously even though nothing is deployed.

## Consequences

**Positive.** An interviewer can run the system in minutes with no barrier. Nothing in the repository can expire. There is no ongoing cost and no maintenance burden on a project that must survive months of intermittent attention. The constraint also forces better engineering: because there is no managed service to hide behind, the outbox, the idempotency store, the health model and the identity issuance all had to be designed rather than purchased — which is precisely what a reviewer wants to see.

**Negative.** No permanently live URL to click. Mitigated by the demo recording embedded in the README and the published cloud-cycle evidence. Some cloud-native reflexes are not exercised — managed service tuning, IAM at scale, multi-availability-zone failure — and this is acknowledged rather than hidden.

**Neutral.** Everything must fit in a laptop's memory, which constrains partition counts and replica counts. These are configuration, and the Kubernetes and Terraform manifests use production-shaped values.

## Alternatives considered

### A permanently hosted demo environment

The strongest immediate impression: a link an interviewer can click without installing anything. Rejected because of decay. A hosted demo requires ongoing money and ongoing attention, and the failure mode — a dead link at the top of the README — is worse than the absence of the link. Recorded evidence of a real deployment captures most of the signal with none of the decay.

### Cloud-only, with no local path

Rejected outright: it makes the project unrunnable by its actual audience.

### Local only, no Terraform at all

The simplest option, and the original plan. Rejected because infrastructure-as-code is a real and expected competency for platform-level roles, and the ephemeral pattern captures it at negligible cost.

### A serverless architecture — Lambda, managed queues, managed databases

Would demonstrate cloud-native design. Rejected because it is architecturally incompatible with running locally, it would replace the interesting engineering with managed-service configuration, and it would tie the project's demonstrable behaviour to a vendor's console.

## Revisit when

A specific opportunity justifies a live environment for a limited period — for instance, standing one up for the duration of an interview process, which the Terraform modules make a single command.
