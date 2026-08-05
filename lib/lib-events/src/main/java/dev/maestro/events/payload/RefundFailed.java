package dev.maestro.events.payload;

/**
 * The refund did not complete.
 *
 * <p>The amount reserved against the payment when the refund was requested is released,
 * so the merchant can try again. Without that release, a failed refund would permanently
 * consume part of the refundable balance for money that was never returned.
 */
public record RefundFailed(
        String refundId,
        String paymentId,
        String merchantId,
        long amountMinor,
        String currency,
        String reason,
        int attemptNo) {
}
