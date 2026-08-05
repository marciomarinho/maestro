package dev.maestro.events.payload;

/**
 * Instructs the router to capture a previously authorized payment.
 *
 * <p>Carries the acquirer and its reference because a capture must go to the institution
 * that holds the authorization — routing is not a fresh decision here, and sending it
 * anywhere else would capture against a hold that does not exist.
 *
 * @param amountMinor the amount to capture, which may be less than was authorized
 */
public record CaptureRequested(
        String paymentId,
        String merchantId,
        long amountMinor,
        String currency,
        String acquirerId,
        String acquirerReference) {
}
