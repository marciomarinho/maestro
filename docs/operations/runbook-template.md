# Runbook: <symptom, as an operator would describe it>

**Severity:** SEV1 (money at risk or merchants blocked) · SEV2 (degraded) · SEV3 (contained)
**Owner:** platform_ops
**Related:** ADR-xxxx · dashboard · alert rule

---

## Signal

What fires. The exact alert name, the metric and its threshold, or the observation that starts this runbook.

## What it means

The user-visible consequence, in one or two sentences. An operator woken at 2am needs to know within ten seconds whether money is at risk.

## Diagnose

Ordered steps, each with the command or query to run and how to read the result. Cheapest and most discriminating checks first.

1. …
2. …

## Likely causes

Ranked by observed frequency, each with the evidence that confirms or eliminates it.

| Cause | Confirms it | Rules it out |
|---|---|---|
| | | |

## Remedy

The action for each cause, with the exact command. State what the action does and what it will look like when it has worked.

## Do not

The tempting actions that make things worse. This section is usually the most valuable one — for example: do not redrive the dead-letter queue before fixing the cause; do not edit ledger postings; do not restart the consumer group to "clear" lag.

## Verify

How to confirm recovery: which metric returns to which range, over what period.

## Escalate

When to stop and get help, and what to capture first — logs, trace identifiers, the state of the affected payments.

## Follow up

What to record afterwards: an incident note, a backlog item, or an ADR if the design needs to change.
