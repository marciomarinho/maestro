# 0016. Separate migration and application database roles for the ledger

- **Status:** Accepted
- **Date:** 2026-08-05

## Context

[ADR-0008](0008-double-entry-projection.md) makes a specific promise about postings:

> Postings are **append-only**: the application's database role holds `INSERT` and `SELECT`
> grants and nothing else. `UPDATE` and `DELETE` are not permitted to fail a code review;
> they are impossible.

Implementing Phase 2 exposed that a single database role per service cannot deliver it.
Flyway must `CREATE TABLE`, which makes the migrating role the table's **owner**, and in
PostgreSQL an owner can always grant privileges back to itself. `REVOKE UPDATE` against the
owner is a suggestion, not a constraint — one `GRANT` statement, or one ORM configured
carelessly, and the promise is gone with nothing to notice it.

So the choice was to weaken the claim in the documentation, or to make it true.

## Decision

**The ledger uses two database roles.**

- `maestro_ledger_migrator` owns schema `ledger` and runs Flyway.
- `maestro_ledger` is what the application connects as. The final section of
  `V1__ledger_schema.sql` grants it exactly what it needs:

```sql
GRANT SELECT, INSERT         ON posting             TO maestro_ledger;
GRANT SELECT, INSERT         ON journal_transaction TO maestro_ledger;
GRANT SELECT, INSERT, UPDATE ON hold                TO maestro_ledger;
GRANT SELECT                 ON fee_schedule        TO maestro_ledger;
```

The absence of `UPDATE` and `DELETE` on `posting` is the enforcement. Spring Boot supports
this directly through `spring.flyway.user`, so it costs two environment variables.

A `BEFORE UPDATE OR DELETE` trigger on `posting` sits behind the grants as belt and braces,
covering anyone who connects as the owner — a human at a psql prompt during an incident,
most likely, which is exactly when the temptation to "just fix the number" is strongest.

**Only the ledger gets the split.** `payment` and `routing` legitimately update their rows:
that is what a guarded state transition *is*. Applying two roles everywhere would be
ceremony that obscures where the property actually matters.

**A test proves it.** `LedgerIntegrityIntegrationTest.postingsAreAppendOnly` connects as
the application role and asserts that both `UPDATE` and `DELETE` on a posting fail. A
promise in a document that nothing verifies is a promise with a short shelf life.

## Consequences

**Positive.** The append-only guarantee holds regardless of application defects, ORM
configuration or a rushed fix during an incident. A correction to the ledger has to be a
reversing transaction, which is what an auditor expects to find. And the claim in ADR-0008
is now literally true rather than aspirational.

**Negative.** Two more credentials for one service, and a schema change requires the
migrator to grant privileges on any new table — a step that is easy to forget and shows up
as a runtime permission error rather than a migration failure. Mitigated by the integration
tests running as the application role, so a missing grant fails the build.

**Neutral.** The split is invisible at runtime; only startup uses the migrator.

## Alternatives considered

### One role, plus the trigger only

Simpler, and the trigger does block the mutation. Rejected as the *primary* mechanism
because a trigger is application-layer-adjacent: it can be dropped or disabled by the same
owner it is meant to constrain, in the same session where someone has already decided to
edit a posting. It is kept as a second line, not the first.

### One role, and soften ADR-0008 to say "by convention"

Honest, and would have cost nothing. Rejected because the append-only property is one of
the few things in this platform that genuinely cannot be recovered by reasoning after the
fact — if postings can be edited, no amount of reconciliation proves anything — and the
mechanism to make it real costs two environment variables.

### Row-level security or a `REVOKE` on the owner

Neither survives ownership. An owner can re-grant, and RLS policies do not apply to the
owner unless `FORCE ROW LEVEL SECURITY` is set, which is a heavier hammer aimed at a
different problem (tenant isolation, ADR-0009).

### A separate database for the ledger

Complete isolation, at the cost of a second instance to run locally and operate. Rejected
under the local-first constraint (ADR-0010); role separation gets the property that matters
without the footprint.

## Revisit when

Another service acquires an append-only table, at which point the pattern should be
extracted rather than copied.
