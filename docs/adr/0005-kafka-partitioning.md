# 0005. Partition by payment, not by merchant

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

Kafka guarantees ordering within a partition and nothing across partitions. The partition key is therefore a decision about which orderings the system is allowed to rely on, and it is close to irreversible once merchants are integrated — changing it means a migration with a quiet period.

Two candidates present themselves, and the reasoning for each is superficially attractive.

**By merchant.** All of one merchant's traffic is ordered and lands on one consumer. It appeals because tenancy feels like the natural unit of a multi-tenant platform.

**By payment.** All operations on a single payment are ordered; different payments are independent.

## Decision

Partition by **`payment_id`**.

The ordering requirement in this domain is per payment and only per payment. A capture must not be processed before the authorization it depends on. A refund must not be processed before the capture. Nothing whatsoever requires payment A of merchant X to be processed before payment B of merchant X — they are unrelated transactions that happen to share a customer.

Partitioning by merchant would provide an ordering guarantee nobody needs, at a real cost:

- **Hot partitions.** Payment volume across merchants is heavily skewed. One large merchant would pin one partition and one consumer thread, while others idle. The system's throughput ceiling would become the largest merchant's throughput ceiling.
- **Scaling coupled to tenancy.** Adding partitions to handle a growing merchant reshuffles every merchant's assignment.
- **Blast radius.** A single poison message stalls that merchant's entire stream rather than one payment.

## Consequences

**Positive.** Even distribution regardless of merchant size mix. Partition count is a capacity decision, decoupled from tenancy. A stuck payment blocks exactly one payment. Consumer parallelism is bounded by partition count rather than by merchant count.

**Negative.** There is no cross-payment ordering guarantee within a merchant. Any future feature that needs it — sequential processing of a merchant's queue, for instance — must build ordering explicitly rather than inherit it. This is documented so nobody assumes otherwise.

**Neutral.** Consumers must be idempotent and must tolerate out-of-order arrival across payments. Both are required regardless of partition key.

## The insight worth stating

The reason merchant-keying is tempting is that it appears to solve **noisy-neighbour fairness** for free: one merchant's flood stays in one partition and cannot starve others. That reasoning is wrong twice over. It does not actually provide fairness — it provides isolation *of the flooding merchant's own throughput*, while the shared consumer pool is still saturated. And it pays for that illusion with the correctness-adjacent cost of hot partitions.

**Ordering and fairness are separate concerns and belong in separate mechanisms.** Ordering is a property of the partition key and should express the true dependency, which is per payment. Fairness is a scheduling property and belongs in the consumer, as weighted scheduling across per-merchant queues. Conflating the two produces a system that does neither well.

Fair scheduling across merchants is not built here — the flagship problem in this project is acquirer routing, not tenant fairness — and it is recorded in [the backlog](../backlog.md) with the design sketched, precisely because the partitioning choice deliberately leaves room for it.

## Alternatives considered

### By merchant

Rejected above.

### By card token

Would order operations against a single card. Rejected: the ordering requirement is per payment, not per card, and it would create hot partitions for high-frequency cards while offering nothing in return.

### Round-robin with no key

Perfect distribution, no ordering at all. Rejected because capture-before-authorization would then be a routine occurrence requiring every consumer to buffer and reorder — pushing complexity into four places to avoid one decision.

## Revisit when

A feature genuinely requires cross-payment ordering within a merchant. Even then, the answer is more likely a per-merchant sequencing mechanism than a change of partition key.
