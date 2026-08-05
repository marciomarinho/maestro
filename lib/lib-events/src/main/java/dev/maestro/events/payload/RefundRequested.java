package dev.maestro.events.payload;

/**
 * Instructs the router to return captured funds to the cardholder.
 *
 * <p>A refund is a money movement in the opposite direction, not an undo — it has its own
 * identifier, its own acquirer call and its own ledger postings. The event is still keyed
 * by <em>payment</em>, so it cannot overtake the capture it reverses (ADR-0005).
 */
public record RefundRequested(
        String refundId,
        String paymentId,
        String merchantId,
        long amountMinor,
        String currency,
        String acquirerId,
        String acquirerReference,
        String reason) {
}
