package dev.maestro.events.payload;

/**
 * The authorization could not be released.
 *
 * <p>The payment stays {@code AUTHORIZED} and the hold stays active. It will lapse on its
 * own at expiry, so a failed void costs the cardholder time rather than money.
 */
public record VoidFailed(
        String paymentId,
        String merchantId,
        String acquirerId,
        String reason,
        int attemptNo) {
}
