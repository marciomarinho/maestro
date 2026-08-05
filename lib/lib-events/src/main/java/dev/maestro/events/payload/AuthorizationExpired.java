package dev.maestro.events.payload;

/**
 * An authorization lapsed before it was captured.
 *
 * <p>Real authorizations do not last forever; a hold the platform never acts on has to be
 * swept, or the ledger accumulates reservations against money nobody will ever take.
 */
public record AuthorizationExpired(
        String paymentId,
        String merchantId,
        long amountMinor,
        String currency) {
}
