# Runbook: consumer lag

**Severity:** SEV2 — payments are being accepted faster than they are being processed.
Merchants see 202s; customers see spinners. The gap is the lag.
**Owner:** platform_ops
**Related:** [ADR-0005](../../adr/0005-kafka-partitioning.md) · [ADR-0006](../../adr/0006-exactly-once-effects.md) · [*dead-letter queue growth*](dead-letter-queue-growth.md)

---

## Signal

`kafka_consumer_fetch_manager_records_lag` rising and staying risen for any of the
three groups (`router`, `payment-api`, `ledger`) — the "Kafka consumer lag" panel on
the golden-signals dashboard. Secondary symptom: payments dwelling in `AUTHORIZING`
while the acquirers are demonstrably healthy.

```bash
docker compose -f deploy/compose/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --all-groups | awk 'NR==1 || $6 > 0'
```

## What it means

Arrival rate has exceeded processing rate for long enough to matter. That has three
usual shapes, and the lag's *distribution across partitions* tells them apart:

- **Every partition lagging evenly** — the consumer is uniformly slow: its dependency
  (database, acquirer) has slowed down, or traffic has genuinely outgrown three
  listener threads.
- **One partition lagging, five fine** — head-of-line blocking: one payment's handler
  is stuck or slow, and everything hashed behind it waits. Partitioning by payment
  (ADR-0005) confines the damage to one-sixth of traffic, but does not eliminate it.
- **Lag sawtoothing after a deploy or rebalance** — the group is rejoining;
  self-resolving within a minute, not an incident.

## Diagnose

1. **Which group, which partitions.** The `kafka-consumer-groups.sh` output above
   answers both. Even lag → step 2. One partition → step 3.

2. **Find what the slow consumer is waiting on.** The listener span in the payment's
   trace shows where the time goes; without a trace, the service's own signals do:
   router → acquirer latency panel and breaker states; ledger and payment-api →
   `hikaricp_connections_pending` and statement latency
   ([*database saturation*](database-saturation.md) if that is where it leads).

3. **For a single stuck partition, identify the payment at the head.** The lagging
   partition's current offset names the record:

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec kafka \
     /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic maestro.payment.commands.v1 --partition <N> --offset <current> \
     --max-messages 1 --property print.key=true
   ```

   Then read that payment's log lines (`payment_id` is on every one) to see what its
   handler is doing. A handler *throwing* is not this runbook: retries land it on the
   DLQ within seconds and unblock the partition by design.

## Act

**If a dependency is slow**, fix the dependency; the lag is a queue draining problem
only after the cause is gone. Lag from a healthy-but-overwhelmed consumer drains at
(processing rate − arrival rate); estimate before acting, because often the right
action is *nothing*.

**If traffic has outgrown the consumers**, raise listener concurrency toward the
partition count (`spring.kafka.listener.concurrency`, currently 3 against 6
partitions) — that is exactly the headroom the partition count was chosen to hold.
Beyond six, more instances; beyond that, more partitions, which is a planned change,
not an incident response.

**If one payment blocks a partition** and its handler is genuinely wedged (not
throwing, not progressing), restart the service instance — the record redelivers, and
either progresses or throws its way to the DLQ. Both outcomes unblock the payments
behind it.

**Do not** rewind or skip offsets to "clear" lag: skipped commands are payments that
silently never complete, which is strictly worse than late ones. Do not scale
consumers past partitions; the extras stand idle and the lag stays.

## Verify

- lag at or returning to zero on every partition
- `AUTHORIZING` dwell time back to seconds
- no growth on the dead-letter topics (unblocking by restart can surface poison —
  that is the [*DLQ growth*](dead-letter-queue-growth.md) runbook doing its job)

## Escalate

Escalate if lag is growing while every dependency is healthy and concurrency is
already at the partition count — that is a capacity ceiling, and the decision to add
partitions belongs in review, not in an incident.

## Notes for the post-incident review

Record the lag's peak, its drain time, and the arrival rate that caused it; the load
report's steady-state figures give the comparison baseline. If a single payment held a
partition, ask why its handler could be slow without throwing — a missing timeout is
the usual answer, and one bounded timeout is worth more than any amount of lag
tooling.
