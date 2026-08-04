package dev.maestro.events.payload;

/**
 * The issuer refused. Final: this payment is not re-attempted anywhere (ADR-0012).
 *
 * <p>Distinct from {@link AuthorizationFailed}, which means no answer was obtained.
 */
public record AuthorizationDeclined(
        String paymentId,
        String merchantId,
        String acquirerId,
        String declineCode,
        String message,
        int attemptNo) {
}
