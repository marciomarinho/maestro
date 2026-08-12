# Chaos experiments

Infrastructure-level faults, injected deliberately, each published with a hypothesis
written **before** the experiment and the observation that followed. A chaos experiment
whose result is narrated after the fact is a demo; the hypothesis is what makes it an
experiment.

The instrument is [Toxiproxy](https://github.com/Shopify/toxiproxy), sitting between
the services and their dependencies via the
[chaos overlay](../../deploy/compose/docker-compose.chaos.yml):

```bash
docker compose -f deploy/compose/docker-compose.yml \
               -f deploy/compose/docker-compose.chaos.yml up -d --wait
```

Application-level faults (decline rates, brownouts, per-acquirer timeouts) are the
acquirer simulator's job and drive the routing demo and the brownout load scenario;
Toxiproxy covers what the simulator cannot — the network between the platform and the
things it depends on.

| Experiment | Fault | Verdict | Script |
|---|---|---|---|
| [01 — Database latency](01-database-latency.md) | +200 ms on payment-api ↔ PostgreSQL | Held | `scripts/chaos/db-latency.sh` |
| [02 — Broker outage](02-kafka-outage.md) | Kafka unreachable by every service | Held | `scripts/chaos/kafka-outage.sh` |
| [03 — Acquirer timeouts](03-acquirer-timeouts.md) | The acquirer network hangs, for everyone | Held | `scripts/chaos/acquirer-timeouts.sh` |

Each script is self-verifying — it states its hypothesis, injects, asserts, heals, and
fails loudly if the platform misbehaves — so re-running an experiment after a change is
one command, and each doc records the run its numbers came from.
