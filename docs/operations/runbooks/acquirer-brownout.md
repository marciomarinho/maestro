# Runbook: acquirer brownout

**Severity:** SEV3 normally, SEV2 if acceptance is falling — the platform is designed to
absorb this without human intervention, so the first job is to confirm it is absorbing it.
**Owner:** platform_ops
**Related:** [ADR-0007](../../adr/0007-adaptive-routing.md) · [ADR-0012](../../adr/0012-never-retry-business-declines.md) · [routing.md](../../architecture/routing.md)

---

## Signal

Any of:

- `maestro_router_corridor_technical_failure_rate` above ~0.2 for a corridor
- `maestro_router_corridor_breaker` at 2 (open) for a corridor
- `maestro_router_retry_budget_utilisation` approaching 1
- an acquirer's share of a corridor collapsing on the routing dashboard

```bash
curl -sS localhost:8081/ops/routing/health \
  -H "Authorization: Bearer $ROUTER_OPS_TOKEN" | jq .
```

## What it means

An acquirer is degraded but still answering. This is the failure mode a health check
cannot see — the acquirer returns `200 OK` to a synthetic probe while declining or
dropping a large share of live transactions.

**The platform is supposed to handle this on its own.** Traffic moves off the degraded
corridor within about a minute, technical failures cascade to the next-best acquirer, and
merchant-visible acceptance holds. The purpose of this runbook is to confirm that is
happening, and to act only if it is not.

## Diagnose

1. **Confirm the router has noticed.** The corridor view shows what it currently believes
   and what it would do about it:

   ```bash
   curl -sS "localhost:8081/ops/routing/corridors/VISA:AUD" \
     -H "Authorization: Bearer $ROUTER_OPS_TOKEN" | jq '.candidates[]'
   ```

   A degraded acquirer should show a rising `technical_failure_rate` and a falling
   `probability`. If its probability is still high, check `samples` — a number near zero
   means the router has almost no evidence and is deciding on the prior.

2. **An acquirer missing from that list has an open breaker.** The corridor view shows only
   candidates, and an open breaker removes the corridor entirely. Use the health view to
   see it:

   ```bash
   curl -sS localhost:8081/ops/routing/health \
     -H "Authorization: Bearer $ROUTER_OPS_TOKEN" | jq '.[] | select(.breaker != "CLOSED")'
   ```

3. **Check what merchants are actually seeing.** This is the number that decides severity.
   Acceptance holding near normal means the platform is doing its job and this is a SEV3
   to be watched, not fixed.

4. **Check the retry budget.** Utilisation near 1 means failover is being refused, which
   turns a single acquirer's problem into failed payments:

   ```bash
   curl -sS localhost:8081/ops/routing/retry-budget \
     -H "Authorization: Bearer $ROUTER_OPS_TOKEN" | jq .
   ```

5. **Check for stranded captures.** This is the part people miss. Only authorization is
   routed — a capture goes to the institution holding the authorization, so payments the
   degraded acquirer had *already authorized* will have their captures fail, and failover
   cannot help. Those payments stay `AUTHORIZED` with the authorization intact.

   Expect a population of `AUTHORIZED` payments on the degraded acquirer that is not
   shrinking. They are not lost and they are not stuck in a queue; they are waiting for
   an acquirer that can serve them.

## Act

**If acceptance is holding:** do nothing to the router. Notify the acquirer through the
normal channel and watch. Intervening here makes things worse — the routing is already
correct and any manual override will still be in place after the acquirer recovers.

**If acceptance is falling**, the usual cause is that there is nowhere left to go: the
other acquirers on the corridor are also unwell, or are not configured for it.

```bash
docker exec maestro-postgres-1 psql -U maestro_routing -d maestro \
  -c "SELECT acquirer_id, corridor, cost_bps, enabled FROM acquirer_corridor ORDER BY corridor, cost_bps;"
```

**If the retry budget is exhausted**, that is the platform protecting itself. Raising
`maestro.router.retry-budget.ratio` will rescue more individual payments and increase load
on the acquirers that are still working — which is the trade the budget exists to prevent
being made accidentally. Do not raise it during an incident without deciding that
deliberately.

**To take an acquirer out entirely** — a decision to stop sending it traffic regardless of
what its health says, for example because the acquirer has asked you to:

```sql
UPDATE acquirer_corridor SET enabled = FALSE
 WHERE acquirer_id = 'southcross' AND corridor = 'VISA:AUD';
```

Takes effect within the corridor refresh interval (60s). **Write down that you did this.**
It is the one routing decision the router will not undo for you, and an acquirer left
disabled after an incident is a permanent, silent cost.

## Recovery

Nothing to do. When the acquirer recovers, the exploration floor detects it within a
minute or two and its share climbs back on its own. If a breaker is open, it half-opens
after 15 seconds and the floor supplies the probe.

If you disabled a corridor manually, re-enable it — and confirm the traffic returns.

## Verify

- `technical_failure_rate` for the corridor back near the 0.02 prior
- breaker `CLOSED`
- the acquirer's `probability` back to roughly its pre-incident share
- retry budget utilisation near zero
- the population of stranded `AUTHORIZED` payments draining as captures are retried

## Escalate

To the acquirer, not internally, in the ordinary case. Escalate internally if acceptance
stays depressed after traffic has moved, which means the problem is not the acquirer —
look for a corridor with only one viable acquirer, or a misconfiguration in
`acquirer_corridor`.

## Notes for the post-incident review

The interesting question after a brownout is rarely "why did it fail" — acquirers fail.
It is **how long the platform took to notice**, which is a property of the half-life, and
**what the exploration floor cost** while the acquirer was broken. Both are measurable
from the attempt history, and both are deliberate choices that can be revisited with
evidence rather than argued about.
