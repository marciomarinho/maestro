package dev.maestro.events.payload;

/** The issuer approved. Funds are reserved; no money has moved yet. */
public record AuthorizationSucceeded(
        String paymentId,
        String merchantId,
        String acquirerId,
        String acquirerReference,
        String authorizationCode,
        long amountMinor,
        String currency,
        int attemptNo) {
}
