package dev.maestro.events.payload;

/**
 * Instructs the router to release an authorization before it is captured.
 *
 * <p>No money has moved, so this reverses nothing — it returns the reserved funds to the
 * cardholder's available balance and releases the ledger's hold.
 */
public record VoidRequested(
        String paymentId,
        String merchantId,
        String acquirerId,
        String acquirerReference) {
}
