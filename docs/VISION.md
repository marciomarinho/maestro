# Vision

## Why this project exists

Maestro is a portfolio flagship. Its purpose is to be *evidence* — of the kind of engineering judgement expected at Staff and Principal level — in a form a stranger can verify in fifteen minutes without taking anything on trust.

That framing drives every decision in this repository. A recruiter or interviewing engineer cannot audit a codebase they have never seen. What they can do is:

1. Read a one-sentence problem statement and immediately recognise it as a real, hard problem.
2. Run one command and watch the system work.
3. Break something on purpose and watch the system react.
4. Read the decision records and see whether the trade-offs were made deliberately or by accident.

Anything in this project that does not serve one of those four things is out of scope.

## What is being built

A **payment orchestration platform**: the layer that sits between merchants and acquiring banks, deciding which acquirer processes each transaction and keeping an authoritative record of the money.

The domain was chosen for three reasons.

**It is credible.** The author works in transactional messaging and has prior payments work; the vocabulary, the failure modes and the operational concerns are lived, not researched. In an interview, domain authority plus a running system is difficult to compete with.

**Money sharpens every problem.** The classic distributed-systems questions — exactly-once, ordering, idempotency, consistency — are often discussed in the abstract. In payments they have consequences an interviewer feels instantly: a lost message is an apology, a duplicate charge is a scandal, and a ledger that does not balance is a regulatory problem. Correctness is not a nice-to-have here; it is the product.

**It is demonstrable.** Because the acquiring banks are simulated and fully controllable, every claim about resilience becomes something you can watch happen. "The system fails over" is a sentence. Degrading an acquirer live and watching the merchant's success rate stay flat is proof.

## Who it is for

| Audience | What they need to see in the first minute |
|---|---|
| **Recruiter / hiring manager** | A clear problem statement, a diagram, a short demo recording, and a repository that plainly is not a tutorial |
| **Interviewing engineer** | Decision records with rejected alternatives, tests that assert hard properties, and code boundaries that hold up |
| **Staff+ interviewer / architect** | Evidence of scope discipline: what was cut, why, and what the documented evolution path is |
| **The author, in an interview** | A concrete system to reason about out loud, with real numbers behind every claim |

## What "done" means

The project is complete when all of the following are true — and not before any of them is true individually, because a half-satisfied criterion is the thing that makes a portfolio project look abandoned.

**Runnable.** One command starts the entire platform on a laptop. A scripted demo takes a payment end to end. A continuous-integration job executes the README quickstart on a schedule, so the documentation cannot silently become false.

**Provable.** Every hard claim has a test or a measurement behind it:
- "Duplicate requests never double-charge" — a concurrency test that fires simultaneous confirmations at real PostgreSQL and asserts a single authorization.
- "The books always balance" — a database constraint that makes an unbalanced transaction impossible to insert, plus a verification job that recomputes balances from postings.
- "The router shifts away from a degrading acquirer" — a test that degrades a simulated acquirer and asserts traffic moves within a bounded time.
- "Merchant A cannot see merchant B's data" — a parameterised isolation test across every merchant-scoped endpoint.
- "It performs" — published load reports with the bottleneck that was found, the fix, and before/after numbers.

**Explained.** Architecture documents with diagrams, sixteen decision records naming what was rejected, operational runbooks for the failure modes that actually occur, and a backlog listing the deliberate omissions.

**Finished-looking at every checkpoint.** Nothing is scaffolded before its phase begins. An unchecked roadmap box reads as planning; an empty directory reads as abandonment.

## Constraints

**Everything runs locally.** The full platform — services, Kafka, PostgreSQL, the observability stack, the portal, every test and every demo — runs on a MacBook via Docker Compose, and later on a local Kubernetes cluster. No feature, test or demo may ever require a cloud account or a third-party credential. The optional Terraform phase deploys the same artefacts to AWS as additional evidence; nothing in the project depends on it.

**No real payment credentials.** Acquiring banks are simulated. This is not a limitation to apologise for — controllable acquirers are what make the routing, resilience and reconciliation stories demonstrable at all.

**Card data never enters the system.** Requests carry opaque tokens. The tokenisation boundary is documented as a first-class architectural concern; see [ADR-0011](adr/0011-simulated-acquirers.md) and the [authorization model](security/authz-model.md).

**Time is the scarcest resource.** This is evening and weekend work over several months. The phase structure exists so that the project is presentable at every point along the way, and so that stopping early is a decision rather than a failure.

## Non-goals

Deliberately not built, each with reasoning recorded in [the backlog](backlog.md) or an ADR:

- Real acquirer or PSP integrations
- A hosted identity provider (self-issued JWTs demonstrate the authorization *model*, which is the interesting part)
- Event sourcing as a framework — the event stream plus a rebuildable ledger projection provides the audit properties without the machinery
- A schema registry, service mesh, or multi-region topology
- 3-D Secure, network tokens, or scheme-specific certification
- Chargeback and dispute management (acknowledged in the domain model, not implemented)
