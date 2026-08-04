# 0012. Never retry a business decline on another acquirer

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

When an authorization fails, the router must decide whether to try a different acquirer. It is tempting to treat every failure the same way — the merchant wants the payment to succeed, and another acquirer might approve it — and a naive implementation of "smart retry" does exactly that.

It is wrong, and the reasons are commercial, regulatory and ethical rather than technical. This is the decision that most clearly separates someone who has built a payments system from someone who has designed one from first principles.

Failures fall into two categories that look similar in a log and are entirely different in meaning.

**A business decline** is an answer from the issuer. *Insufficient funds. Card expired. Do not honour. Suspected fraud. Card reported stolen.* The transaction was evaluated by the party entitled to evaluate it, and refused.

**A technical failure** is the absence of an answer. A timeout, a connection reset, a `503`, a malformed response. Nobody decided anything; the platform simply failed to obtain a decision.

## Decision

**Cascading failover applies to technical failures only. A business decline terminates the payment.**

The rule is enforced in the router, asserted by an integration test, and stated in the merchant-facing documentation.

### Why

**It is prohibited.** Card scheme rules limit re-presentation of declined authorizations. Certain decline codes — stolen card, pick up card, fraud suspected — carry an explicit prohibition on retrying at all, through any channel. Codes indicating a permanent condition, such as a closed account, may not be retried without a change in circumstances. Systematic re-presentation across acquirers to obtain a different answer attracts penalties and, sustained, jeopardises the acquiring relationship itself.

**It is fraud-adjacent.** If an issuer declines a card as reported stolen and the platform routes the same transaction to a different acquirer hoping for approval, the platform is shopping for a bank that has not yet caught up. Whatever the intent, that is the behaviour, and it is indistinguishable from an attack the fraud systems exist to stop.

**It damages the merchant.** Issuers track authorization attempts per card. Repeated declines within a short window raise the card's risk profile and reduce approval rates for that cardholder's *future*, legitimate transactions — including with the same merchant. Retrying a decline optimises one payment at the expense of subsequent ones.

**It does not work.** The issuer's answer does not depend on which acquirer relayed the question. Insufficient funds are insufficient regardless of the route. The retry burns a network fee, adds latency to a customer who is watching a spinner, and reaches the same conclusion.

### Classification

Acquirer responses are mapped to a sealed set of outcomes in `lib-domain`, so a new response code cannot be silently absorbed into the wrong bucket — an unmapped code is a compile-time or startup failure, not a runtime guess:

| Outcome | Retryable elsewhere | Examples |
|---|---|---|
| `APPROVED` | — | |
| `DECLINED_BUSINESS` | **Never** | Insufficient funds, expired card, do not honour, suspected fraud, stolen card, restricted card |
| `DECLINED_TECHNICAL` | **Yes** | Issuer unavailable, system malfunction, routing error, acquirer internal error |
| `TIMEOUT` | Yes, with the same acquirer idempotency key | No response within the deadline |
| `THROTTLED` | Yes, after backoff | `429`, capacity exceeded |

`DECLINED_TECHNICAL` is the subtle case: the issuer's own systems failed, so no evaluation occurred and another acquirer may reach the issuer by a different path. It is a technical failure wearing a decline's clothing, and classifying it correctly is exactly the kind of detail that distinguishes a working router from a plausible one.

**Timeouts retry against the same acquirer first**, carrying the same idempotency key, because the transaction may already have been authorized. Failing over to a different acquirer on a timeout without first resolving the ambiguity risks authorizing twice — one of the few ways this system could take a customer's money twice.

### Merchant-initiated retry is a different thing

A merchant may legitimately re-attempt a declined payment later — after a customer tops up their account, or on a dunning schedule for a subscription. That is a **new payment**, created deliberately by the merchant with its own idempotency key, subject to scheme rules on re-presentation. It is not the router silently retrying, and the distinction is preserved in the API: the router never creates a payment.

## Consequences

**Positive.** Scheme-compliant. No fraud vector. No degradation of cardholders' future approval rates. No wasted network fees or latency. The behaviour is explainable to a merchant, an acquirer and a compliance reviewer in one sentence each.

**Negative.** Some payments that another acquirer might have approved will not be. This is correct, and the small number of such cases is not revenue the platform is entitled to capture by re-asking a question that was already answered.

**Neutral.** Response-code classification must be maintained per acquirer, since codes are not fully standardised across institutions. The mapping is data, tested per acquirer.

## Alternatives considered

### Retry everything

The naive "maximise approval rate" approach. Rejected on every ground above.

### Retry a configurable subset of decline codes

A middle path: allow retry on `do not honour`, which is genuinely ambiguous and sometimes transient. Rejected because it puts a compliance-sensitive decision into a configuration file where it will be widened under commercial pressure, one code at a time, by someone who does not know why the list was short. The rule is safer as code with a test than as configuration with a comment.

### Retry with a delay on the same acquirer

Legitimate in narrow cases and the basis of dunning strategies. Rejected as a router behaviour because it belongs to the merchant's billing logic, not to the transaction path — a checkout cannot wait, and the merchant owns the decision to ask again.

## Revisit when

Never, in substance. The classification mapping evolves as acquirers are added; the rule does not.
