package dev.maestro.acquirersim.api;

/**
 * An acquirer's answer.
 *
 * <p>{@code outcome} distinguishes an issuer decision from a systems failure, because
 * the platform treats them completely differently: a decline is final everywhere, a
 * technical failure may be tried on another acquirer (ADR-0012).
 *
 * @param outcome          {@code APPROVED}, {@code DECLINED_BUSINESS},
 *                         {@code DECLINED_TECHNICAL} or {@code THROTTLED}
 * @param acquirerReference the acquirer's own reference; the key reconciliation matches on
 * @param retryAfterMillis how long the acquirer asks the caller to wait; throttling only
 */
public record AcquirerResponse(
        String outcome,
        String acquirerReference,
        String authorizationCode,
        String responseCode,
        String responseMessage,
        Long retryAfterMillis) {

    public static AcquirerResponse approved(String acquirerReference, String authorizationCode) {
        return new AcquirerResponse(
                "APPROVED", acquirerReference, authorizationCode, "00", "Approved", null);
    }

    public static AcquirerResponse businessDecline(String declineCode, String message) {
        return new AcquirerResponse("DECLINED_BUSINESS", null, null, declineCode, message, null);
    }

    public static AcquirerResponse technicalFailure(String code, String message) {
        return new AcquirerResponse("DECLINED_TECHNICAL", null, null, code, message, null);
    }

    /** Refused on capacity grounds. Nothing was attempted, so nothing was decided. */
    public static AcquirerResponse throttled(long retryAfterMillis) {
        return new AcquirerResponse(
                "THROTTLED", null, null, "THROTTLED",
                "Capacity exceeded; retry later or elsewhere", retryAfterMillis);
    }

    /**
     * Whether the issuer actually reached a verdict.
     *
     * <p>Only a decision is worth remembering against an idempotency key. Replaying a
     * failure to a caller that is entitled to retry would make every retry path in the
     * platform permanently unrecoverable.
     */
    public boolean isDecision() {
        return "APPROVED".equals(outcome) || "DECLINED_BUSINESS".equals(outcome);
    }
}
