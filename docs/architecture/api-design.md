# API Design

The merchant-facing HTTP contract, its cross-cutting semantics, and the design rules it is held to. The API is the part of a payments platform that customers integrate against once and live with for years, so the conventions here are chosen for longevity rather than convenience.

Related reading: [domain model](../domain.md) · [authorization model](../security/authz-model.md) · [ADR-0013 idempotency](../adr/0013-idempotency-in-postgres.md)

---

## 1. Design rules

**Resources and state, not remote procedures.** A payment is a resource with a lifecycle. Operations that transition it (`confirm`, `capture`, `void`) are sub-resource actions, because they are not idempotent creations of a new thing and pretending otherwise with `PUT` obscures the state machine.

**Versioned in the path, additive within a version.** `/v1/...`. Within `v1`, fields are only ever added; nothing is removed or has its meaning changed. A breaking change means `/v2`, running alongside.

**Explicit units, always.** Every monetary field is named `amount_minor`, is an integer, and is accompanied by `currency`. There is no field named `amount`, because a client would guess at its unit.

**Snake case in JSON, ISO 8601 UTC timestamps with an offset, opaque string identifiers with a type prefix** (`pay_`, `ref_`, `mch_`, `evt_`). Prefixes make identifiers self-describing in logs and support conversations, and make it impossible to pass a refund identifier where a payment identifier belongs without noticing.

**Asynchronous truth, synchronous acknowledgement.** Confirming a payment returns `202` with the payment in `AUTHORIZING`. The outcome arrives by webhook, by polling, or over the portal's event stream. The API never blocks a merchant's request thread on an acquiring bank.

**Errors are machine-readable.** RFC 9457 `application/problem+json` with a stable `type` URI and a stable `code`. Error codes are part of the contract and are documented in a catalogue.

---

## 2. Authentication

Two credential types, one authorization model. Details in the [authorization model](../security/authz-model.md).

| Caller | Credential | Header |
|---|---|---|
| Merchant server | API key | `Authorization: Bearer sk_live_...` |
| Portal user | JWT (RS256, JWKS-published) | `Authorization: Bearer eyJ...` |

Every request resolves to a principal carrying a merchant scope and a permission set. There is no unauthenticated endpoint other than health and the JWKS document.

---

## 3. Endpoints

### Payments

| Method | Path | Permission | Notes |
|---|---|---|---|
| `POST` | `/v1/payments` | `payment:write` | Create. `Idempotency-Key` required. `confirm: true` creates and confirms in one call |
| `GET` | `/v1/payments/{id}` | `payment:read` | |
| `GET` | `/v1/payments` | `payment:read` | Cursor paginated; filters on status, date range, amount, acquirer, card network |
| `POST` | `/v1/payments/{id}/confirm` | `payment:write` | `CREATED` → `AUTHORIZING`. Returns `202` |
| `POST` | `/v1/payments/{id}/capture` | `payment:capture` | Optional `amount_minor` for partial capture |
| `POST` | `/v1/payments/{id}/void` | `payment:void` | Releases the authorization |
| `GET` | `/v1/payments/{id}/attempts` | `payment:read` | The routing audit trail: which acquirers were tried, why each was chosen, what each answered |

`GET /v1/payments/{id}/attempts` is unusual for a payments API and deliberate. Merchants integrating with orchestration platforms are routinely unable to find out *why* a payment took the path it took. Exposing the attempt history — including the selection reason and the health score at decision time — turns the router from a black box into something a merchant's support team can reason about.

**Create a payment**

```http
POST /v1/payments
Authorization: Bearer sk_live_...
Idempotency-Key: 6f0a1c2e-...
Content-Type: application/json

{
  "amount_minor": 1999,
  "currency": "AUD",
  "card_token": "tok_visa_4242",
  "reference": "order-10432",
  "capture_method": "automatic",
  "confirm": true,
  "metadata": { "order_id": "10432", "channel": "web" }
}
```

```http
HTTP/1.1 202 Accepted
Location: /v1/payments/pay_01HQ8X5K2M
Idempotency-Replayed: false

{
  "id": "pay_01HQ8X5K2M",
  "status": "AUTHORIZING",
  "amount_minor": 1999,
  "currency": "AUD",
  "captured_amount_minor": 0,
  "refunded_amount_minor": 0,
  "card": { "network": "VISA", "last4": "4242", "country": "AU" },
  "reference": "order-10432",
  "metadata": { "order_id": "10432", "channel": "web" },
  "created_at": "2026-08-04T09:41:12.402Z"
}
```

`capture_method` is `automatic` (capture immediately on authorization) or `manual` (the merchant captures later — the model for shipping-on-dispatch).

### Refunds

| Method | Path | Permission |
|---|---|---|
| `POST` | `/v1/payments/{id}/refunds` | `refund:write` |
| `GET` | `/v1/refunds/{id}` | `refund:read` |
| `GET` | `/v1/payments/{id}/refunds` | `refund:read` |

Refunding is separated from `payment:write` because it moves money outward. A key that can take payments should not automatically be able to return them; see the [permission matrix](../security/authz-model.md).

### Balances, payouts, reconciliation

| Method | Path | Permission |
|---|---|---|
| `GET` | `/v1/balances` | `balance:read` |
| `GET` | `/v1/payouts` · `/v1/payouts/{id}` | `payout:read` |
| `GET` | `/v1/payouts/{id}/statement` | `payout:read` |
| `GET` | `/v1/reconciliation/runs` | `reconciliation:read` |
| `GET` | `/v1/reconciliation/runs/{id}/discrepancies` | `reconciliation:read` |
| `POST` | `/v1/reconciliation/discrepancies/{id}/resolve` | `reconciliation:approve` |

### Credentials and webhooks

| Method | Path | Permission |
|---|---|---|
| `POST` · `GET` · `DELETE` | `/v1/api-keys` | `apikey:manage` |
| `POST` · `GET` · `DELETE` | `/v1/webhook-endpoints` | `webhook:manage` |
| `GET` | `/v1/webhook-deliveries` | `webhook:read` |
| `POST` | `/v1/webhook-deliveries/{id}/retry` | `webhook:manage` |

An API key's secret is returned exactly once, at creation. Thereafter only its prefix and metadata are retrievable.

### Portal event stream

| Method | Path | Permission |
|---|---|---|
| `GET` | `/v1/events/stream` | `payment:read` |

Server-sent events, scoped to the caller's merchant, resumable with `Last-Event-ID`. This is what drives the portal's live payment feed and the routing visualisation during the brownout demo.

### Operations surface

Separated under `/ops` because it is cross-merchant and carries different authorization semantics entirely.

| Method | Path | Permission |
|---|---|---|
| `GET` | `/ops/acquirers` | `acquirer:read` |
| `PATCH` | `/ops/acquirers/{id}` | `acquirer:manage` |
| `GET` | `/ops/acquirers/health` | `acquirer:read` |
| `GET` | `/ops/dlq` | `dlq:read` |
| `POST` | `/ops/dlq/redrive` | `dlq:manage` |
| `POST` | `/ops/reconciliation/runs` | `reconciliation:approve` |

`acquirer-sim` exposes its own fault-injection API on a separate port, never mounted in the merchant surface, and documented in the [runbooks](../operations/README.md) as a demo instrument.

---

## 4. Idempotency

The single most important semantic in a payments API.

**Required on every state-changing request.** `POST` without an `Idempotency-Key` is rejected with `400`. Making it optional invites merchants to omit it precisely on the endpoints where it matters.

**Scope:** merchant + endpoint + key. The same key on a different endpoint is a different operation.

**Lifecycle:**

1. The key is claimed by inserting a record with status `IN_PROGRESS`, in the same transaction that performs the business change.
2. On completion, the response body and status are stored against the record.
3. A replay with a matching request fingerprint returns the stored response with `Idempotency-Replayed: true`.
4. A replay with a *different* fingerprint returns `409 idempotency_key_reuse`. Silently returning the first response to a genuinely different request would be a correctness bug that hides merchant errors.
5. A replay arriving while the original is still `IN_PROGRESS` returns `409 idempotency_request_in_progress` with `Retry-After`.
6. Records are retained for 24 hours, then swept.

Because the record is written in the same transaction as the effect, there is no window in which the effect exists but the key does not. This is the reason idempotency lives in PostgreSQL rather than in a cache; see [ADR-0013](../adr/0013-idempotency-in-postgres.md).

---

## 5. Errors

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json

{
  "type": "https://maestro.dev/errors/insufficient-authorized-amount",
  "title": "Capture exceeds authorized amount",
  "status": 422,
  "code": "capture_exceeds_authorized",
  "detail": "Capture of 2500 AUD minor units exceeds the authorized 1999.",
  "instance": "/v1/payments/pay_01HQ8X5K2M/capture",
  "request_id": "req_01HQ8X6P9Q"
}
```

`code` is stable and machine-readable; `title` and `detail` are for humans and may be reworded. `request_id` appears on every response, error or not, and is the value a merchant quotes to support — it correlates to the trace.

| Status | When |
|---|---|
| `400` | Malformed request, missing idempotency key |
| `401` | Missing or invalid credential |
| `403` | Authenticated, but lacking the required permission |
| `404` | Not found — **also returned for resources belonging to another merchant**, deliberately, so the API does not confirm the existence of another tenant's records |
| `409` | Idempotency conflict, or a state-machine violation such as capturing a voided payment |
| `422` | Semantically invalid: amount exceeds authorization, currency mismatch, unsupported corridor |
| `429` | Rate limited, with `Retry-After` |
| `502` / `504` | Acquirer failure surfaced synchronously — rare, since the path is asynchronous |

A **business decline is not an error.** `POST /confirm` returns `202`, and the payment later reaches `DECLINED` with a `decline_code`. Modelling a declined card as an HTTP error conflates "the issuer said no", which is a normal business outcome, with "the request was wrong", and forces merchants to parse error bodies to run their checkout.

---

## 6. Pagination, filtering, rate limits

**Cursor pagination**, never offset. Offset pagination silently skips or repeats rows when the underlying set changes between pages, which for a payment list under live traffic is guaranteed.

```http
GET /v1/payments?limit=50&status=CAPTURED&created_after=2026-08-01T00:00:00Z
```

```json
{
  "data": [ ... ],
  "has_more": true,
  "next_cursor": "cur_eyJpZCI6InBheV8wMUhR..."
}
```

The cursor is opaque and encodes the sort key plus the identifier, so it is stable under concurrent inserts.

**Rate limits** are per merchant, returned on every response as `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`, with `429` and `Retry-After` on exhaustion.

---

## 7. Webhooks

Merchants receive `payment.authorized`, `payment.declined`, `payment.failed`, `payment.captured`, `payment.voided`, `payment.expired`, `refund.succeeded`, `refund.failed`, `payout.created`.

```http
POST /their/endpoint
Maestro-Signature: t=1754301672,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
Maestro-Event-Id: evt_01HQ8X7T2V
Content-Type: application/json
```

**Signature:** HMAC-SHA256 over `{timestamp}.{raw_body}` using the endpoint's secret. Merchants must compare in constant time and reject timestamps outside a tolerance window, which prevents replay of a captured payload. The scheme is deliberately Stripe-shaped: it is well understood, and the verification code merchants already have works.

**Delivery guarantees:** at least once. Events carry `event_id` and merchants are told to deduplicate on it. Retries use exponential backoff with jitter over roughly 24 hours; every attempt is recorded and visible in the portal, and can be replayed manually.

**Ordering is not guaranteed.** Each event carries the full current state of the resource plus `occurred_at`, so an out-of-order arrival is detectable and discardable by the recipient. Promising ordered webhook delivery over an unreliable network to an endpoint that may be down for hours is a promise no platform can keep.

---

## 8. Documentation and testing of the contract

An OpenAPI 3.1 document is generated from the code and committed, so contract changes appear in diffs and are reviewable. Contract tests assert that the published document matches actual behaviour, including error shapes. The error-code catalogue lives alongside it, and adding an undocumented code fails the build.
