# Runbook: database saturation

**Severity:** SEV1 if payment creation is failing or breaching its SLO; SEV2 if
latency is elevated with headroom left. PostgreSQL is the one dependency every service
shares, so its saturation is everyone's incident at once.
**Owner:** platform_ops
**Related:** [chaos 01 — database latency](../../chaos/01-database-latency.md) · [ADR-0016](../../adr/0016-separate-migration-and-application-roles.md)

---

## Signal

`hikaricp_connections_pending` above zero for any service — threads queueing for a
connection — or payment creation p99 drifting up with no matching acquirer or Kafka
signal. Both are on the golden-signals dashboard.

The chaos figure to keep in mind: **+25 ms of statement latency is enough to break the
150 ms creation SLO** (a creation is ~6 round trips), and at +200 ms each creation
holds a connection for ~1.2 s, so 16 concurrent checkouts exhaust a service's pool of
16. Latency and pool exhaustion are the same incident a few minutes apart.

## What it means

Demand for connections exceeds supply. The distinction that drives everything below is
**slow statements** (each connection held longer, pool starves at constant traffic)
versus **more traffic** (pool starves at constant statement latency). The dashboard
separates them: statement latency up → slow statements; latency flat while pending
rises → volume.

## Diagnose

1. **Who is starving, and how badly.**

   ```bash
   for svc in 8080 8081 8083; do
     curl -sS localhost:$svc/actuator/metrics/hikaricp.connections.pending \
       | jq -r '"\(.availableTags[]?|select(.tag=="pool").values[0] // "pool") pending=\(.measurements[0].value)"'
   done
   ```

   One service starving → its workload; all three → PostgreSQL itself.

2. **Ask PostgreSQL what it is doing**, not what clients feel:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec postgres \
     psql -U maestro -d maestro -c \
     "SELECT pid, state, wait_event_type, wait_event, now() - query_start AS running, left(query, 60)
        FROM pg_stat_activity WHERE state <> 'idle' ORDER BY running DESC LIMIT 15;"
   ```

   - Many rows in `active` with `Lock` waits → contention: find the blocker with
     `pg_blocking_pids(pid)`. In this schema the usual suspect is a long transaction
     holding a payment row while something queues behind the guarded update.
   - `IO` waits dominating → storage is the ceiling.
   - Nothing slow server-side while clients starve → the pool itself is sized below
     the workload, or a client is leaking connections (Hikari logs leak warnings).

3. **Check the obvious background suspects**: the ledger's balance verification
   recomputes every balance from postings (5-minute cadence), and the outbox sweep
   deletes published rows at 03:00. Either coinciding with the incident window is a
   scheduling answer, not a capacity one.

## Act

**Kill the offending query, not the database.** A single runaway is ended with
`SELECT pg_cancel_backend(pid)` (escalating to `pg_terminate_backend` if it ignores
the cancel); every idempotency and outbox mechanism is built to absorb the retry.

**If it is volume**, raise the affected service's pool cautiously
(`spring.datasource.hikari.maximum-pool-size`) — but arithmetic first: pools are 16
per service and PostgreSQL's `max_connections` is finite. A pool raised past what the
database can concurrently *execute* converts queueing in the application (visible,
bounded, per-service) into queueing inside PostgreSQL (invisible, shared). Often the
correct response is to accept elevated latency until the burst passes — the outbox
means nothing is lost, merely late.

**Do not** "fix" saturation by restarting PostgreSQL: every in-flight transaction
rolls back, every pool reconnects at once, and the stampede recreates the saturation
with interest. And per the operational posture, never an `UPDATE` to financial rows to
route around a lock — a correction is a reversing transaction.

## Verify

- `hikaricp_connections_pending` at zero everywhere
- creation p99 back under 150 ms
- outbox oldest-age back to seconds (the relay was competing for the same
  connections), consumer lag drained

## Escalate

Escalate if saturation recurs at the same hour (a scheduled collision needs a
schedule fix), or if IO waits dominate at traffic the load report says should be
comfortable — that is capacity planning, with the report as the baseline.

## Notes for the post-incident review

Save `pg_stat_statements`' top offenders from the window. The double-capture and
refund races are protected by guarded updates that *serialise on the payment row* —
correct by design, and precisely where a slow statement turns into a lock convoy, so
"which statement got slow" matters more here than in most systems.
