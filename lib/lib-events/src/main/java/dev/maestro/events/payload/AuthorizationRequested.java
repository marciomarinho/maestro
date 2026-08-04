package dev.maestro.events.payload;

/**
 * Instructs the router to obtain an authorization for a payment.
 *
 * <p>Carries only a card <em>token</em>. No field in any event, log or table in this
 * platform can hold a card number (ADR-0011).
 */
public record AuthorizationRequested(
        String paymentId,
        String merchantId,
        long amountMinor,
        String currency,
        String cardToken,
        String cardNetwork,
        String captureMethod) {
}
