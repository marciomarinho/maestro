package dev.maestro.payment.security;

/**
 * The authenticated caller.
 *
 * <p>Both credential types — API keys now, JWTs from Phase 6 — resolve to this one
 * type, so every authorization check downstream is written once and holds for both.
 *
 * @param merchantId the tenant scope; every merchant-scoped query is filtered on it
 * @param credentialId which key or token authenticated, for the audit trail
 * @param role         resolved role name; the full permission model arrives in Phase 6
 */
public record MerchantPrincipal(String merchantId, String credentialId, String role) {
}
