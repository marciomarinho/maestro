package dev.maestro.events.payload;

/**
 * No answer could be obtained from any acquirer that was tried.
 *
 * <p>Unlike a decline, nothing was decided — {@code attempts} records how many
 * acquirers were asked before the platform gave up.
 */
public record AuthorizationFailed(
        String paymentId,
        String merchantId,
        String reason,
        int attempts) {
}
