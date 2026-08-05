package dev.maestro.events.payload;

/**
 * Funds have been taken. This is the first event in a payment's life that moves money,
 * and therefore the first that produces ledger postings (ADR-0008).
 */
public record CaptureSucceeded(
        String paymentId,
        String merchantId,
        String acquirerId,
        String acquirerReference,
        long amountMinor,
        String currency,
        int attemptNo) {
}
