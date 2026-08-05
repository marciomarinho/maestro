package dev.maestro.events.payload;

/** Funds were returned to the cardholder. Produces ledger postings in the opposite
 * direction to the capture, including a proportional return of the platform's fee. */
public record RefundSucceeded(
        String refundId,
        String paymentId,
        String merchantId,
        String acquirerId,
        String acquirerReference,
        long amountMinor,
        String currency,
        int attemptNo) {
}
