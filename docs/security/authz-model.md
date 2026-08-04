# Authorization Model

Who may do what, to whose data, and how that is enforced in more than one place so a single mistake is not a breach.

The design goal is that **tenant isolation is not something a developer has to remember**. Any model where correctness depends on every author adding `WHERE merchant_id = ?` will eventually leak, because one query will eventually be written without it. The enforcement here is layered, and the outermost layer is the database.

Related reading: [API design](../architecture/api-design.md) · [ADR-0009 RBAC design](../adr/0009-rbac-and-tenancy.md)

---

## 1. Principals and credentials

| Principal | Credential | Used by | Lifetime |
|---|---|---|---|
| **Merchant service account** | API key, `sk_live_` prefix, stored as a hash | Merchant backends calling the REST API | Until revoked |
| **Portal user** | JWT, RS256, published JWKS | Humans in the merchant portal | 15 minutes, with refresh |
| **Platform user** | JWT with no merchant scope | Operators and auditors | 15 minutes, with refresh |

Both credential types resolve to the same internal `Principal`: a subject, an optional merchant scope, a role, and a resolved set of permissions. Everything downstream of authentication is credential-agnostic — which means the permission checks are tested once and hold for both.

**API keys** are shown once at creation and stored only as a hash with a display prefix. A key carries a role, so a merchant can issue a read-only key for a reporting job and a payment-taking key for checkout without one being able to do the other's work.

**JWTs** are self-issued by `payment-api` from a local key pair, with a JWKS endpoint for verification. This is a deliberate choice: the interesting engineering is the *authorization model*, not integrating a hosted identity provider, and requiring an external IdP would break the constraint that everything runs locally. Swapping the issuer for Keycloak, Auth0 or Cognito changes only the token-validation configuration; the claims contract is documented so the migration is mechanical. See [ADR-0009](../adr/0009-rbac-and-tenancy.md).

**Claims:**

```json
{
  "sub": "usr_01HQ8X9A3B",
  "merchant_id": "mch_01HQ8W2C4D",
  "role": "merchant_admin",
  "permissions": ["payment:read", "payment:write", "refund:write", "..."],
  "iss": "https://maestro.local",
  "aud": "maestro-api",
  "exp": 1754302572,
  "iat": 1754301672,
  "jti": "tok_01HQ8XB5E6"
}
```

`merchant_id` is absent for platform roles. Permissions are embedded so the hot path performs no lookup, at the cost of a revocation delay bounded by the fifteen-minute token lifetime — an explicit trade-off, with a deny-list for immediate revocation of a compromised token by `jti`.

---

## 2. Roles

Four roles, drawn from how payments organisations actually divide responsibility.

| Role | Scope | Purpose |
|---|---|---|
| `merchant_admin` | One merchant | Full control of their own account: money actions, credentials, users |
| `merchant_developer` | One merchant | Build and operate the integration: read everything, manage API keys and webhooks — but **cannot move money** |
| `platform_ops` | All merchants | Keep the platform running: acquirer configuration, dead-letter redrive, reconciliation, cross-merchant read — but **cannot move a merchant's money** |
| `auditor` | All merchants | Read everything including the ledger. Zero write permissions anywhere |

Two of these encode separation of duties, which is the part that matters.

**`merchant_developer` cannot issue refunds.** The engineer wiring up the integration has no business returning money; that is a finance or support action. This split is what a payments compliance reviewer looks for first.

**`platform_ops` cannot issue refunds either.** An operator can redrive a stuck queue and disable a failing acquirer, but cannot reach into a merchant's account and move their funds. A platform operator with unilateral money-movement power over every tenant is an audit finding waiting to happen; the correct remedy for a merchant's money problem is an adjustment with a recorded approval, not an operator refund.

---

## 3. Permission matrix

Authorization is checked on **permissions**, never on roles. `@PreAuthorize("hasAuthority('refund:write')")`, never `hasRole('ADMIN')`. Roles are a packaging convenience that will change; permissions are the stable contract, and checking roles directly is what makes adding a fifth role a codebase-wide edit.

| Permission | `merchant_admin` | `merchant_developer` | `platform_ops` | `auditor` |
|---|:---:|:---:|:---:|:---:|
| `payment:read` | ✅ | ✅ | ✅ | ✅ |
| `payment:write` | ✅ | ✅ | — | — |
| `payment:capture` | ✅ | ✅ | — | — |
| `payment:void` | ✅ | ✅ | — | — |
| `refund:read` | ✅ | ✅ | ✅ | ✅ |
| `refund:write` | ✅ | — | — | — |
| `balance:read` | ✅ | ✅ | ✅ | ✅ |
| `payout:read` | ✅ | — | ✅ | ✅ |
| `ledger:read` | ✅ | — | ✅ | ✅ |
| `apikey:manage` | ✅ | ✅ | — | — |
| `webhook:manage` | ✅ | ✅ | — | — |
| `webhook:read` | ✅ | ✅ | ✅ | ✅ |
| `user:manage` | ✅ | — | — | — |
| `acquirer:read` | — | — | ✅ | ✅ |
| `acquirer:manage` | — | — | ✅ | — |
| `dlq:read` | — | — | ✅ | ✅ |
| `dlq:manage` | — | — | ✅ | — |
| `reconciliation:read` | ✅ | — | ✅ | ✅ |
| `reconciliation:approve` | — | — | ✅ | — |
| `audit:read` | ✅ | — | ✅ | ✅ |

Merchant-scoped permissions apply *within the principal's merchant*. `payment:read` for `merchant_admin` means their own payments; for `platform_ops` it means all merchants. The scope comes from the principal, not from the permission.

---

## 4. Enforcement — four layers

A single check is a single point of failure. Each layer below would independently prevent a cross-tenant read.

### Layer 1 — Endpoint permission checks

Every controller method carries an explicit authorization annotation. There is no default-permit path: an ArchUnit rule fails the build if a request-mapped method lacks one, so forgetting is a compile-time-adjacent error rather than a production incident.

### Layer 2 — Central tenant scoping

The authenticated principal populates a request-scoped `TenantContext`. Repository access for merchant-scoped entities goes through a base type that applies the merchant predicate automatically; a query that needs to cross merchants must be written against an explicitly named, permission-guarded API. Developers do not hand-write the scoping filter, so they cannot forget it.

### Layer 3 — PostgreSQL row-level security

The application connects as a role that has row-level security enabled on merchant-scoped tables, with the current merchant set as a session variable inside the transaction. Policies restrict visibility to matching rows. **A query that somehow escapes layer 2 returns zero rows rather than another tenant's data.**

This layer is tested by deliberately bypassing the application: connecting directly with the application role, setting a merchant context, and asserting that another merchant's rows are invisible even to raw SQL.

### Layer 4 — Response filtering and existence hiding

A resource belonging to another merchant returns `404`, not `403`. Returning `403` confirms that the identifier exists, which is an enumeration oracle: an attacker could map the platform's payment volume by probing identifiers and distinguishing the two responses. The tenant-isolation test suite asserts `404` specifically, on every merchant-scoped endpoint, parameterised so a new endpoint is covered the moment it is added.

---

## 5. Audit logging

Every privileged action writes an audit record **in the same database transaction as the action itself**, so an action cannot exist without its audit trail. Failing to log fails the operation.

Recorded: actor, credential type and identifier, role, action, target resource, before and after values where applicable, request identifier, source address, and timestamp.

Audited: refunds, payouts, ledger adjustments, API-key creation and revocation, user and role changes, acquirer configuration changes, dead-letter redrives, reconciliation resolutions, and every action taken by a platform role against merchant data.

The audit log is append-only, readable by `auditor` and `merchant_admin` (own merchant only), and writable by no one.

---

## 6. Card data and PCI scope

**No card number ever enters this system.** The API accepts opaque tokens; there is no field anywhere in the schema, in an event envelope or in a log that can hold a primary account number. The platform stores only network, last four digits and issuing country — non-sensitive metadata that is nonetheless enough to route intelligently, since a corridor is defined by network and currency.

Tokenisation is performed by a vault outside the trust boundary, simulated here. This is how real orchestration platforms minimise their PCI scope, and it is a design decision worth stating explicitly rather than an omission.

Supporting measures: request and response bodies are logged with a field allow-list rather than a deny-list, so a newly added sensitive field is excluded by default; error messages never echo credentials; and API-key secrets are unrecoverable after creation.

---

## 7. What is deliberately not built

- **A hosted identity provider.** Self-issued JWTs demonstrate the model and keep the platform runnable offline. The migration path is documented.
- **Multi-factor authentication and password policy.** Standard, well-understood, and adds no signal.
- **Field-level encryption at rest.** There is no sensitive field to encrypt — that is the point of the tokenisation boundary.
- **Fine-grained per-resource permissions.** Role plus tenant scope is the right granularity at this size; per-resource grants would be complexity without a driving requirement.

---

## 8. Tests that defend this model

| Test | Asserts |
|---|---|
| Tenant isolation, parameterised over every merchant-scoped endpoint | Merchant A's token receives `404` for merchant B's resources |
| Row-level security bypass test | Raw SQL as the application role cannot see another merchant's rows |
| ArchUnit: annotated endpoints | Every request-mapped method has an explicit authorization annotation |
| ArchUnit: permissions not roles | No `hasRole` usage anywhere |
| Permission matrix test, generated from the table above | Each role can perform exactly its permitted actions and receives `403` on the rest |
| Audit completeness test | Every privileged action produces exactly one audit record, in the same transaction |
| Token handling | Expired, malformed, wrong-audience and revoked tokens are all rejected |
| API-key secrecy | A created key's secret is unrecoverable through any endpoint |
