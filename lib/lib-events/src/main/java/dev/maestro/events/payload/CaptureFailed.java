package dev.maestro.events.payload;

/**
 * The capture did not complete.
 *
 * <p>The authorization survives, so the payment returns to {@code AUTHORIZED} and the
 * merchant may try again — unlike a failed authorization, nothing is lost but time.
 */
public record CaptureFailed(
        String paymentId,
        String merchantId,
        String acquirerId,
        String reason,
        int attemptNo) {
}
