# Domain Model

The vocabulary, entities, invariants and money-handling rules of the platform. Payments has a precise language; using it loosely is how systems end up with a `status` column that means three different things. This document is the reference the code is held to.

Related reading: [architecture overview](architecture/overview.md) · [API design](architecture/api-design.md) · [ADR-0003 money representation](adr/0003-money-as-minor-units.md) · [ADR-0008 ledger design](adr/0008-double-entry-projection.md)

---

## 1. Vocabulary

**Merchant** — a customer of the platform. The tenant boundary: every payment, refund, balance and API key belongs to exactly one merchant.

**Acquirer** (acquiring bank) — the institution that processes card transactions on the merchant's behalf and eventually pays out the funds. Maestro holds relationships with several and chooses between them.

**Issuer** — the cardholder's bank. It approves or declines. Maestro never talks to it directly; the issuer's answer arrives through the acquirer and the card network.

**Corridor** — an acquirer combined with a card network and currency, for example `northbank × VISA × AUD`. Health, cost and capacity are properties of a corridor, not of an acquirer as a whole: an acquirer can be perfectly healthy for domestic Visa traffic and failing for cross-border Mastercard.

**Authorization** — a request to the issuer to reserve funds. It produces a *hold* on the cardholder's available balance and an authorization code. **No money moves.** Authorizations expire, typically in days.

**Capture** — the instruction to actually take the authorized funds. This is the point at which money moves and the point at which the merchant becomes owed. May be for less than the authorized amount (partial capture); the remainder is released.

**Void** (cancellation) — releasing an authorization before capture. Nothing was ever taken.

**Refund** — returning captured funds to the cardholder. A separate money movement in the opposite direction, not an undo. May be partial and may occur several times up to the captured amount.

**Settlement** — the acquirer transferring the net funds for a batch of captured transactions, usually daily, accompanied by a file itemising what was included and what was deducted. Settlement lags capture by days.

**Payout** — the platform transferring the merchant's balance to the merchant's bank account, net of fees.

**Business decline** — the issuer said no: insufficient funds, card expired, suspected fraud, do-not-honour. A definitive answer. Retrying it elsewhere is prohibited; see [ADR-0012](adr/0012-never-retry-business-declines.md).

**Technical failure** — no answer was obtained: a timeout, a connection reset, a `503` from the acquirer, a malformed response. The transaction's fate is *unknown*, which makes it the hardest case in payments and the only one where another acquirer may be tried.

**Chargeback** — the cardholder disputes a transaction and the issuer forcibly reverses it. Acknowledged in this model, deliberately not implemented; see [the backlog](backlog.md).

---

## 2. Money

Money is the one thing in this system that must never be approximately right.

**Represented as an integer count of minor units plus an ISO 4217 currency code.** `Money(1999, "AUD")` is $19.99. There is a single `Money` value type in `lib-domain`; a bare number never crosses a boundary. Floating point does not appear anywhere in the money path — not in Java, not in JSON, not in the database, not in the portal. See [ADR-0003](adr/0003-money-as-minor-units.md).

**Rules:**

1. **Currencies never mix implicitly.** Adding `AUD` to `USD` throws. There is no ambient default currency, and no conversion in this system at all.
2. **Minor-unit scale comes from the currency**, not from a constant. `JPY` has zero decimal places, `AUD` two, `BHD` three. Formatting for display is the only place scale matters, and it is derived from the currency code.
3. **Division always states its rounding.** Fee calculation is the only place division occurs. The rounding mode and the allocation of remainders are explicit and tested — because a percentage fee on an odd amount is precisely where cents go missing.
4. **The database stores `BIGINT` minor units and a `CHAR(3)` currency.** Never `NUMERIC`, never `FLOAT`.
5. **JSON uses `amount_minor` as an integer, always paired with `currency`.** The field name carries the unit so no client can misread it.
6. **Sums of money in a journal transaction must be exactly zero.** Not "within a tolerance". Exactly.

---

## 3. Entities

### Merchant context — owned by `payment-api`

**Merchant** — the tenant. Identifier, display name, status (`ACTIVE`, `SUSPENDED`), default currency, fee schedule reference, timestamps.

**ApiKey** — server-to-server credential. Stores a display prefix and a hash; the secret is shown once at creation and never retrievable. Belongs to a merchant, has a role, may be revoked. Used by merchant backends.

**UserAccount** — a human. Belongs to a merchant, or to the platform for `platform_ops` and `auditor` roles. Holds credentials and role assignments. Used by the portal.

**Payment** — the central aggregate.

| Field | Notes |
|---|---|
| `id` | Stable public identifier |
| `merchant_id` | Tenant scope; every query is filtered on it |
| `amount_minor`, `currency` | The authorized amount |
| `captured_amount_minor` | Accumulates across partial captures |
| `refunded_amount_minor` | Accumulates across partial refunds |
| `card_token`, `card_network`, `card_last4`, `card_country` | Token plus non-sensitive metadata; never a card number |
| `status` | The state machine in [the architecture overview](architecture/overview.md#3-the-payment-state-machine) |
| `authorized_at`, `authorization_expires_at` | Holds expire |
| `acquirer_id`, `acquirer_reference` | The acquirer that ultimately succeeded, and its reference — the key reconciliation matches on |
| `decline_code`, `failure_reason` | Populated on `DECLINED` / `FAILED` |
| `metadata` | Merchant-supplied key/value pairs, opaque to the platform |
| `created_at`, `updated_at`, `version` | Optimistic locking on `version` |

**Invariants:**
- `captured_amount_minor ≤ amount_minor`
- `refunded_amount_minor ≤ captured_amount_minor`
- `currency` is immutable after creation
- Status transitions follow the state machine; every transition is a guarded conditional update
- A payment in `AUTHORIZED` past `authorization_expires_at` is swept to `EXPIRED`

**Refund** — `id`, `payment_id`, `merchant_id`, `amount_minor`, `currency`, `reason`, `status` (`PENDING`, `SUCCEEDED`, `FAILED`), `acquirer_reference`, timestamps. The sum of a payment's succeeded refunds may never exceed its captured amount — enforced by a guarded update on the payment row, not by a read-then-write.

**IdempotencyRecord** — `merchant_id` + `key` + `endpoint` as the unique constraint, plus a hash of the request body, the stored response, its status code, and the resulting resource identifier. Written in the same transaction as the effect it guards. A replay with a *different* body under the same key is a `409`, not a silent success — an important distinction, since silently returning the first response to a genuinely different request is itself a correctness bug.

**OutboxEvent** — `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `trace_context`, `created_at`, `published_at`. Inserted in the business transaction, published by the relay afterwards, claimed with `FOR UPDATE SKIP LOCKED` so multiple instances cooperate without a leader.

**WebhookEndpoint / WebhookDelivery** — merchant callback URLs with a signing secret, and the delivery log with attempt counts, response codes and next-retry timing.

**AuditLog** — actor, role, action, target, before/after where applicable, request identifier, timestamp. Append-only. Every privileged action writes one.

### Routing context — owned by `router`

**Acquirer** — identifier, display name, status (`ENABLED`, `DISABLED`, `PROBING`), base endpoint, supported corridors.

**AcquirerCorridor** — acquirer × card network × currency, plus commercial and operational configuration: fee basis points, fixed fee minor units, capacity in transactions per second, priority weight, whether the corridor is enabled.

**PaymentAttempt** — the audit trail behind every routing decision, and one of the more interesting tables in the system.

| Field | Notes |
|---|---|
| `payment_id`, `attempt_no` | Unique together; `attempt_no` drives the acquirer idempotency key |
| `operation` | `AUTHORIZE`, `CAPTURE`, `REFUND`, `VOID` |
| `acquirer_id`, `corridor` | Who was chosen |
| `selection_reason` | `BEST_SCORE`, `EXPLORATION`, `FAILOVER`, `PINNED` — so a decision can always be explained afterwards |
| `health_score_at_selection` | The score that justified the choice, frozen at decision time |
| `outcome` | `APPROVED`, `DECLINED`, `TECHNICAL_FAILURE`, `TIMEOUT`, `THROTTLED` |
| `response_code`, `response_message` | Scheme-style codes from the acquirer |
| `latency_ms` | Feeds the health model |
| `acquirer_reference` | Present on success |
| `started_at`, `completed_at` | |

Capturing `selection_reason` and the score at selection time is what makes the routing behaviour auditable rather than mysterious — it turns "why did this go to Northbank?" into a query.

**AcquirerHealth** — per corridor, the EWMA state: approval rate, technical failure rate, latency percentiles, sample counts, last updated. Persisted so a restarting router does not begin blind.

**BreakerState** — per corridor: `CLOSED`, `OPEN`, `HALF_OPEN`, with transition timestamps and consecutive-failure counts.

### Ledger context — owned by `ledger`

**Account** — the chart of accounts. `id`, `type` (`ASSET`, `LIABILITY`, `REVENUE`, `EXPENSE`), `scope` (platform-wide or merchant-scoped), `merchant_id` where applicable, `currency`, `normal_balance` (debit or credit).

The chart:

| Account | Type | Normal | Meaning |
|---|---|---|---|
| `acquirer_receivable:{acquirer}` | Asset | Debit | Funds the acquirer owes the platform for captured transactions not yet settled |
| `platform_cash` | Asset | Debit | Funds actually received from acquirers |
| `merchant_payable:{merchant}` | Liability | Credit | Funds the platform owes the merchant |
| `platform_fee_revenue` | Revenue | Credit | Fees earned |
| `refund_clearing:{acquirer}` | Liability | Credit | Refunds sent to the acquirer, not yet reflected in settlement |

**JournalTransaction** — a balanced set of postings. `id`, `source_event_id` (unique — this is what makes ledger writes idempotent under event replay), `type` (`CAPTURE`, `REFUND`, `SETTLEMENT`, `PAYOUT`, `ADJUSTMENT`, `REVERSAL`), `payment_id` or `settlement_id` as reference, `occurred_at`, `recorded_at`.

**Posting** — `id`, `transaction_id`, `account_id`, `direction` (debit or credit), `amount_minor`, `currency`. Append-only: no `UPDATE`, no `DELETE`, enforced by database grants. A mistake is corrected with a reversing transaction, which is what an auditor expects to see.

**Hold** — an authorization reservation. `payment_id`, `merchant_id`, `amount_minor`, `currency`, `status` (`ACTIVE`, `RELEASED`, `CAPTURED`, `EXPIRED`), `expires_at`. **Deliberately not a journal transaction**, because no money has moved. Modelling authorizations as postings is the most common way a payments ledger ends up wrong — it inflates balances with money nobody has.

**FeeSchedule** — per merchant and corridor: basis points and fixed component, with the rounding rule.

**SettlementFile / SettlementLine** — the acquirer's daily statement and its rows: acquirer reference, gross, fee, net, transaction date, and the matching status assigned during reconciliation.

**ReconciliationRun / Discrepancy** — the run's window, counts and drift totals; and each unmatched item with its classification: `MISSING_IN_LEDGER`, `MISSING_IN_FILE`, `AMOUNT_MISMATCH`, `FEE_MISMATCH`, `DUPLICATE`, `TIMING_DIFFERENCE`. Each has an assignee, a resolution and a note.

**Payout** — merchant, period, gross, fees, refunds, net, status, statement reference.

---

## 4. How events become postings

| Event | Ledger effect |
|---|---|
| `AuthorizationSucceeded` | Create a `Hold`. **No postings.** |
| `AuthorizationExpired` / `PaymentVoided` | Release the hold. **No postings.** |
| `CaptureSucceeded` | Consume the hold, then post: **DR** `acquirer_receivable` gross · **CR** `merchant_payable` net · **CR** `platform_fee_revenue` fee |
| `RefundSucceeded` | **DR** `merchant_payable` refunded net · **DR** `platform_fee_revenue` fee returned per policy · **CR** `refund_clearing` total |
| `SettlementReceived` | **DR** `platform_cash` net received · **DR** `platform_fee_revenue` acquirer's own fees · **CR** `acquirer_receivable` gross |
| `PayoutExecuted` | **DR** `merchant_payable` · **CR** `platform_cash` |

Every one of these carries the originating `event_id` as `source_event_id`. A replayed event violates the unique constraint and is skipped — which is the entire mechanism by which at-least-once delivery yields exactly-once money effects.

---

## 5. System invariants

These are the properties the test suite exists to defend. Each names how it is enforced.

| # | Invariant | Enforcement |
|---|---|---|
| 1 | Every journal transaction's postings sum to zero, per currency | Database constraint, checked at commit |
| 2 | Postings are never updated or deleted | Database grants: `INSERT` and `SELECT` only |
| 3 | An event produces at most one journal transaction | Unique constraint on `source_event_id` |
| 4 | A payment is authorized at most once | Guarded state transition plus the acquirer idempotency key |
| 5 | Captured never exceeds authorized | Guarded conditional update on the payment row |
| 6 | Refunded never exceeds captured | Guarded conditional update on the payment row |
| 7 | A business decline is never retried on another acquirer | Router logic, asserted by test |
| 8 | Money never changes currency | The `Money` type; mixed-currency operations throw |
| 9 | Merchant A can never read merchant B's data | Central scoping plus PostgreSQL row-level security |
| 10 | Every privileged action is recorded | Audit log written in the same transaction as the action |
| 11 | Balances derived from postings equal materialised balances | Verification job, drift metric, alert |
| 12 | Card numbers never enter the system | Token-only API contract; no field accepts a PAN |

---

## 6. Deliberate simplifications

Recorded here so a reader knows they were choices, not oversights. Full reasoning in [the backlog](backlog.md).

- **Single currency per merchant.** Multi-currency accounts and FX are a substantial domain in their own right; the ledger is designed with `currency` on every account and posting so the extension is additive.
- **No chargebacks or disputes.** Modelled in the vocabulary, not implemented.
- **No 3-D Secure or strong customer authentication.** Would change the authorization flow shape without adding to the routing or ledger story.
- **Fees are flat basis points plus a fixed component.** Real interchange-plus pricing with scheme fees, cross-border loading and tiered volume discounts is a rabbit hole with no additional engineering signal.
- **Payouts are recorded, not executed.** There is no bank integration; the payout is a ledger movement and a statement.
