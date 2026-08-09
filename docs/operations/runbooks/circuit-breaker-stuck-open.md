# Runbook: circuit breaker stuck open

**Severity:** SEV2 — an acquirer is receiving no traffic at all. If it is the cheapest or
the only one on a corridor, this is costing money or failing payments outright.
**Owner:** platform_ops
**Related:** [ADR-0007](../../adr/0007-adaptive-routing.md) · [routing.md](../../architecture/routing.md) · `CircuitBreakers`

---

## Signal

`maestro_router_corridor_breaker` at 2 (open) for a corridor for longer than a couple of
minutes, or an acquirer persistently absent from a corridor's candidate list.

```bash
curl -sS localhost:8081/ops/routing/health \
  -H "Authorization: Bearer $ROUTER_OPS_TOKEN" | jq '.[] | select(.breaker != "CLOSED")'
```

## What it means

Five consecutive unanswered calls opened the breaker, which removes the corridor from
selection **entirely** — exploration floor and all. This is the one place traffic is cut
to zero rather than reduced.

A breaker is designed to cycle: open for 15 seconds, then half-open, which readmits the
corridor to selection where its ruined score earns it the exploration floor. That floor
*is* the probe. One answer closes it; one failure reopens it.

So a breaker that is open for a few seconds at a time is working. A breaker that is
**continuously** open means every probe is failing, which means the acquirer genuinely is
not answering — or the router cannot reach it, which is a different problem with the same
symptom and is the one worth ruling out first.

## Diagnose

1. **Distinguish cycling from stuck.** Sample the state a few times:

   ```bash
   for _ in $(seq 1 10); do
     curl -sS localhost:8081/ops/routing/health -H "Authorization: Bearer $ROUTER_OPS_TOKEN" \
       | jq -r '.[] | select(.breaker != "CLOSED") | "\(.acquirer_id) \(.breaker)"'
     sleep 3
   done
   ```

   Alternating `OPEN` and `HALF_OPEN` is the breaker working: it is probing and the probes
   are failing. Permanently `OPEN` with no half-open transition is the anomaly, and points
   at the router rather than the acquirer.

2. **Ask the acquirer directly**, bypassing the router entirely. This is the step that
   separates "the acquirer is down" from "we cannot reach the acquirer":

   ```bash
   curl -sS -m 5 "$ACQUIRER_BASE_URL/actuator/health"
   ```

   Remember that a brownout passes this check. A healthy response here alongside failing
   probes means the acquirer is answering synthetic requests and failing real ones — that
   is [*acquirer brownout*](acquirer-brownout.md), not this runbook.

3. **Check the router can resolve and reach it.** A DNS or network change looks exactly
   like an acquirer outage from inside the breaker:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec router \
     curl -sS -m 5 http://acquirer-sim:8082/actuator/health
   ```

4. **Read the attempt history for the failures.** The response code distinguishes a real
   acquirer error from a client-side one:

   ```sql
   SELECT acquirer_id, outcome, response_code, response_message, count(*)
     FROM payment_attempt
    WHERE started_at > now() - interval '10 minutes'
      AND outcome <> 'APPROVED'
    GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC;
   ```

   `ACQUIRER_ERROR` or `UNMAPPED_OUTCOME` in bulk suggests the platform's problem, not the
   acquirer's — a contract change, a serialisation failure, or a misconfigured base URL.
   `ISSUER_UNAVAILABLE` and `TIMEOUT` suggest theirs.

5. **Check the corridor is configured at all.** An acquirer with no `acquirer_corridor` row
   is not a candidate and never will be, which looks similar on a dashboard but is not a
   breaker problem:

   ```sql
   SELECT acquirer_id, corridor, enabled FROM acquirer_corridor ORDER BY corridor;
   ```

## Act

**If the acquirer is genuinely down:** nothing to do here. The breaker is correct, traffic
has moved, and it will close on its own when the acquirer answers a probe. Work the
incident with the acquirer.

**If the router cannot reach a healthy acquirer**, this is a platform fault. Fix the
connectivity — base URL, DNS, network policy, certificate — and the breaker closes on the
next probe without further action.

**If the failures are client-side** (`ACQUIRER_ERROR`, `UNMAPPED_OUTCOME`), the acquirer
changed something the client does not understand, or a deployment broke the mapping. Roll
back the router if a deployment correlates. There is no configuration change that fixes a
broken response mapping.

**There is no endpoint to force a breaker closed, deliberately.** A breaker held closed by
hand against an acquirer that is failing sends real customer payments into a black hole,
and the override outlives the memory of whoever set it. If an acquirer must be excluded,
do it explicitly and durably:

```sql
UPDATE acquirer_corridor SET enabled = FALSE
 WHERE acquirer_id = 'southcross' AND corridor = 'VISA:AUD';
```

Restarting the router does clear breaker state, since it is per instance and in memory.
That is a blunt instrument and not a fix: if the acquirer is still failing, the breaker
reopens within five attempts, and you have discarded every corridor's health along with it.

## Verify

- breaker `CLOSED` for the corridor
- `technical_failure_rate` falling back toward the 0.02 prior
- the acquirer's `probability` climbing as evidence accumulates
- `samples` rising — a number near zero means the reading is still assumption

## Escalate

Escalate internally if the breaker is open on a corridor with **no other enabled
acquirer**, because those payments are failing outright rather than being rerouted. Check
before assuming there is an alternative:

```sql
SELECT corridor, count(*) FILTER (WHERE enabled) AS viable
  FROM acquirer_corridor GROUP BY corridor HAVING count(*) FILTER (WHERE enabled) < 2;
```

## Notes for the post-incident review

If the breaker opened on an acquirer that was healthy, the threshold is wrong for the
traffic pattern — five consecutive failures is a short run on a busy corridor and a long
one on a quiet one. That is a real tuning question and the attempt history has the data to
answer it. Resist the urge to raise the threshold during the incident; write it down and
change it with evidence.
