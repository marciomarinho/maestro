# Runbook: outbox relay stalled

**Severity:** SEV1. Payments are being accepted and *nothing downstream is hearing
about them* — every one is a 202 the platform has not yet made true. The blast radius
is every payment accepted since the stall.
**Owner:** platform_ops
**Related:** [ADR-0004](../../adr/0004-transactional-outbox.md) · [ADR-0018](../../adr/0018-trace-context-through-the-outbox.md) · [chaos 02 — broker outage](../../chaos/02-kafka-outage.md)

---

## Signal

`maestro_outbox_oldest_age_seconds` climbing. Age is the signal, not the count:
a healthy relay under a burst shows high *pending* with a young oldest row; a stalled
relay shows the oldest row ageing linearly whatever the count is doing. Payments
dwelling in `AUTHORIZING` with zero consumer lag is the same fact seen from the other
side — the consumers are starving, not slow.

```bash
curl -sS localhost:8080/actuator/metrics/maestro.outbox.oldest.age | jq '.measurements[0].value'
```

(Each outbox-owning service — payment-api and the router — has its own relay and its
own gauges. Which service's age is climbing scopes everything below.)

## What it means

Rows are committing to the outbox table and not leaving it. The relay is a small loop
— claim with an advisory lock, publish synchronously, mark published — so the ways it
stalls are few:

- **The broker is unreachable or refusing sends.** The chaos 02 experiment is this
  exact fault, deliberately: pending grows, age grows, and recovery is automatic when
  the broker returns.
- **A send is hanging rather than failing.** The relay publishes synchronously
  (ordering demands it), so one send that neither succeeds nor fails holds that relay
  pass for up to the producer's two-minute delivery timeout per attempt.
- **The relay is not running at all** — scheduling stopped with the service otherwise
  healthy (thread exhaustion, a scheduler seized by another `@Scheduled` task that
  never returns).

## Diagnose

1. **Is the relay trying and failing, or not trying?** The relay logs every failed
   pass loudly:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml logs --since 10m payment-api \
     | grep "Outbox relay pass failed"
   ```

   Failures present → broker path (step 2). Silence while age climbs → the relay is
   not running (step 3).

2. **Broker path.** Is it reachable, and are the topics writable?

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec kafka \
     /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null \
     && echo broker answers
   ```

   Broker healthy but sends failing names the narrower problem in the exception —
   authorisation, a deleted topic, message size.

3. **Not-running path.** Thread dump the service and look for the scheduler:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec payment-api \
     jcmd 1 Thread.print | grep -A 12 "scheduling-"
   ```

   A scheduler thread parked in *another* scheduled method is the classic finding —
   fixed-delay tasks share the scheduler, so one wedged task starves the drains
   scheduled beside it.

4. **Rule out the impostor.** `pg_locks` showing another instance's advisory locks on
   the aggregates is *normal* (that is instances sharing work). Every row skipped on
   locks held by a **crashed** connection would mean PostgreSQL has not yet reaped it;
   transaction-scoped locks die with their transaction, so this resolves itself within
   the connection's timeout.

## Act

**Broker down:** work the broker. The outbox is doing its job — nothing is lost, the
backlog is the design absorbing the outage (chaos 02 measured a 20-payment backlog
draining in 5 seconds; the drain rate is the batch size over the send latency, so even
hours of backlog clear in minutes).

**Relay wedged or not scheduling:** restart the affected service. This is one of the
rare stalls where restart *is* the fix rather than the reflex — the relay is
stateless, claims are transaction-scoped locks that die with their holder, and
publish-then-mark means the worst case is republished duplicates, which consumers
absorb (ADR-0006).

**Do not** publish rows to Kafka by hand or mark rows published to make the gauge look
better — a row marked published that never reached the broker is the one loss mode the
design has, and it requires an operator to create it.

## Verify

- `maestro_outbox_oldest_age_seconds` back to single digits and `maestro_outbox_pending`
  near zero on the affected service
- the payments accepted during the stall progressing out of `AUTHORIZING` —
  spot-check the oldest one's attempt history
- consumer lag briefly spiking as the backlog lands, then draining: the queue moving
  one stage downstream, as designed

## Escalate

Escalate if rows age while the relay logs *successful* passes — claimed, published,
marked, yet `published_at` stays null — because that contradicts the mechanism itself
and means something (a second writer, clock skew, a schema surprise) is lying to you.

## Notes for the post-incident review

If the stall was a wedged neighbouring `@Scheduled` task, give that task a timeout or
its own executor. If it was the broker, this incident is chaos 02 replayed in
production — compare the drain curve against the experiment's and update the
experiment doc if reality disagreed with it.
