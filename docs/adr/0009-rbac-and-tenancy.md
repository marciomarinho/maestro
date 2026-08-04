# 0009. Permission-based RBAC with layered tenant isolation

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

Maestro is multi-tenant and it holds money. Two questions must be answered with more rigour than a typical application requires: *who may perform this action*, and *whose data may they see*.

The second is the dangerous one. In any model where isolation depends on developers remembering to filter by tenant, one query will eventually be written without the filter — in a reporting endpoint, an admin tool, or a hastily added `findAll`. The consequence is a cross-tenant data leak, which for a payments platform is an existential event rather than a bug.

The authorization model also has to express something specific to financial systems: **separation of duties**. Not everyone who can operate the platform should be able to move money, and not everyone who can integrate with it should be able to return it.

## Decision

### Authorization on permissions, never on roles

Checks are written as `hasAuthority('refund:write')`, never `hasRole('ADMIN')`. Roles are bundles of permissions — a packaging convenience that will change as the product grows. Permissions are the stable contract. Checking roles directly means every new role is a codebase-wide edit, and it obscures what an endpoint actually requires. An ArchUnit rule fails the build on any use of `hasRole`.

### Four roles encoding separation of duties

`merchant_admin`, `merchant_developer`, `platform_ops`, `auditor`. The full matrix is in [the authorization model](../security/authz-model.md).

Two constraints in that matrix are the point of having roles at all:

**`merchant_developer` cannot issue refunds.** The engineer building the integration has no business returning money; that is a finance or support action. This is the first split a payments compliance reviewer looks for.

**`platform_ops` cannot issue refunds either.** An operator may redrive a stuck queue and disable a failing acquirer, but may not reach into a merchant's account and move their funds. A platform operator with unilateral money-movement power over every tenant is an audit finding. The correct remedy for a merchant's money problem is a recorded adjustment with approval, not an operator refund.

### Tenant isolation in four layers

Any one of these would prevent a cross-tenant read. All four are present, because the cost of the failure justifies the redundancy.

1. **Endpoint permission checks.** Every request-mapped method carries an explicit authorization annotation; an ArchUnit rule fails the build if one is missing, so there is no default-permit path.
2. **Central scoping.** The authenticated principal populates a request-scoped tenant context, and merchant-scoped repository access applies the predicate automatically. Developers do not hand-write the filter, so they cannot forget it. Crossing merchants requires an explicitly named, permission-guarded API.
3. **PostgreSQL row-level security.** The application role has row-level security policies on merchant-scoped tables, with the current merchant set as a transaction-local session variable. **A query that escapes layer two returns zero rows rather than another tenant's data.** This is verified by a test that bypasses the application entirely and queries with raw SQL.
4. **Existence hiding.** Another merchant's resource returns `404`, not `403`, so the API does not confirm that an identifier exists.

Layer three is the one that makes the difference. Layers one and two are code, and code has defects. Layer three holds even when the code is wrong.

### Self-issued JWTs

Tokens are issued by `payment-api` from a local key pair, with a JWKS endpoint for verification. Permissions are embedded in the token so the request path performs no lookup; the cost is a revocation delay bounded by the fifteen-minute token lifetime, mitigated by a `jti` deny-list for immediate revocation.

API keys serve server-to-server traffic, stored as hashes with a display prefix and carrying a role, so a merchant can issue a read-only reporting key and a payment-taking checkout key separately.

## Consequences

**Positive.** Adding a role is a data change, not a code change. Isolation survives a developer mistake. The `403`-versus-`404` distinction removes an enumeration oracle. Separation of duties is demonstrable to a compliance-minded interviewer, which is a differentiator in fintech hiring.

**Negative.** Row-level security adds a session-variable set per transaction and a small query-planning cost, and it makes some debugging less obvious — a query returning nothing may be policy rather than data, which the runbooks call out explicitly. Embedded permissions delay revocation by up to the token lifetime.

**Neutral.** Self-issued tokens mean no external identity provider, which is what keeps the platform runnable offline.

## Alternatives considered

### Role checks at endpoints

The common shortcut. Rejected: it couples every endpoint to the role taxonomy and makes the taxonomy effectively immutable.

### Application-layer tenant filtering only

Layers one, two and four without row-level security. Rejected because it makes isolation depend entirely on code correctness. Row-level security is cheap, and the failure it prevents is unrecoverable — a leaked cross-tenant payment record cannot be un-leaked.

### A database schema or instance per tenant

The strongest possible isolation. Rejected: migration across thousands of schemas is operationally miserable, cross-tenant platform queries become impossible, and connection-pool management degrades badly. The right answer for a handful of very large enterprise tenants, not for a payments platform with many merchants.

### A hosted identity provider — Keycloak, Auth0, Cognito

Production-appropriate and what a real deployment would use. Rejected because it would break the constraint that everything runs locally with no external account, and because the engineering interest here is the authorization model rather than token issuance. The claims contract is documented so swapping the issuer changes only validation configuration.

### An external policy engine — Open Policy Agent, Cedar

Powerful for complex policy, with policy as testable data. Rejected as disproportionate: the policy here is a static role-to-permission matrix plus tenant scoping, which a table expresses more clearly than a policy language. Worth revisiting if per-resource or attribute-based rules appear.

## Revisit when

Per-resource or attribute-based permissions are needed, merchants require custom roles, or a real identity provider is introduced.
