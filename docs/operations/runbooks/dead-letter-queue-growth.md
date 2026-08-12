# Runbook: dead-letter queue growth

**Severity:** SEV2 — every record on a DLQ is a payment whose lifecycle has stopped
moving. Nothing is lost yet, and nothing is progressing either.
**Owner:** platform_ops
**Related:** [ADR-0006](../../adr/0006-exactly-once-effects.md) · `MessagingAutoConfiguration` · `DlqRedriveService`

---

## Signal

`maestro_dlq_depth` above zero and rising, per dead-letter topic
(`maestro.payment.commands.dlq.v1`, `maestro.payment.events.dlq.v1`).

```bash
curl -sS localhost:8081/actuator/prometheus | grep maestro_dlq_depth
```

## What it means

A consumer threw on the same record through three retries with backoff, so the error
handler parked it on the dead-letter topic instead of the two alternatives, both worse:
blocking the partition (every payment behind it stops) or skipping (the record is gone
and a payment is stuck in a half-state with no evidence).

The retries have already ruled out a blip. What is left is one of two shapes, and the
distinction drives everything below:

- **Poison** — the record itself can never be processed: malformed payload, an
  unparseable envelope, a defect in the handler for that event type. Depth grows slowly
  and the failures all share an event type or a producer.
- **Outage** — the records are fine and the thing the handler needs is not: database
  down, schema migration half-applied, a dependency unreachable. Depth grows fast and
  the failures share nothing except a timestamp.

## Diagnose

1. **Read the dead letters.** The record carries its body and, in `kafka_dlt-*`
   headers, where it died and why:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec kafka \
     /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic maestro.payment.commands.dlq.v1 --from-beginning --max-messages 5 \
     --property print.headers=true --property print.key=true
   ```

   `kafka_dlt-exception-message` names the throw; `kafka_dlt-original-topic` names the
   scene. One event type recurring is poison; a spread of types sharing a window is an
   outage.

2. **Find the failing consumer's own account.** The exception header is truncated; the
   consumer's log has the stack trace, correlated by the payment id in the record key:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml logs router payment-api ledger \
     | grep '<payment id from the record key>'
   ```

3. **If it looks like an outage, confirm the dependency**, not the symptom — check the
   database and the downstream the handler touches. The
   [*database saturation*](database-saturation.md) and
   [*consumer lag*](consumer-lag.md) runbooks take over if that is where this leads.

## Act

**Fix the cause first. Redrive second. The order is the whole runbook.**

- **Outage:** restore the dependency, watch a fresh record process successfully, then
  redrive. Nothing about the parked records needs changing.
- **Poison from a handler defect:** fix and deploy the handler, then redrive. The
  records were always valid; the code was not.
- **Genuinely malformed records:** these will dead-letter again on every redrive, by
  design. That cycle is safe — each pass goes through a human — but the record needs a
  decision, not a retry: fix the producer that emitted it, and reconcile the affected
  payment through the attempt history.

Then return everything to its topic:

```bash
curl -sS -X POST localhost:8081/ops/dlq/redrive \
  -H "Authorization: Bearer $ROUTER_OPS_TOKEN"
```

The response reports how many records went back, per topic. Redrive is idempotent from
the platform's side: progress commits per batch, a crashed redrive resumes where it
stopped, and a redelivered record is absorbed by the same consumer idempotency that
absorbs any duplicate (ADR-0006).

**Do not** clear a DLQ by deleting the topic or moving the redrive group's offsets by
hand. Both destroy the only record of payments that were promised an outcome. Depth
returning to zero through redrive is recovery; depth returning to zero any other way is
evidence tampering.

## Verify

- `maestro_dlq_depth` at zero for both topics
- the redriven payments moving again — attempt history advancing, statuses leaving
  `AUTHORIZING`/`CAPTURING`
- no immediate re-growth, which would mean the cause was not actually fixed

## Escalate

Escalate if depth is growing faster than it can be diagnosed, or if the dead letters
are on the **commands** topic during business hours — those are customer-facing
authorizations queuing behind a defect, and the merchant-visible failure the platform
exists to prevent.

## Notes for the post-incident review

Every dead letter is a message the platform did not understand at the moment it
arrived. If the cause was a deploy, the gap is in integration tests — the poison test
in `DeadLetterIntegrationTest` shows the pattern for pinning the specific failure. If
the cause was an outage, ask whether the retry budget of three-with-backoff matched the
outage's shape, with the caution that longer in-listener retries hold partitions
hostage while they run.
