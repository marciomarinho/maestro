package dev.maestro.acquirersim.api;

/**
 * An acquirer's answer.
 *
 * <p>{@code outcome} distinguishes an issuer decision from a systems failure, because
 * the platform treats them completely differently: a decline is final everywhere, a
 * technical failure may be tried on another acquirer (ADR-0012).
 *
 * @param outcome          {@code APPROVED}, {@code DECLINED_BUSINESS} or {@code DECLINED_TECHNICAL}
 * @param acquirerReference the acquirer's own reference; the key reconciliation matches on
 */
public record AuthorizeResponse(
        String outcome,
        String acquirerReference,
        String authorizationCode,
        String responseCode,
        String responseMessage) {

    public static AuthorizeResponse approved(String acquirerReference, String authorizationCode) {
        return new AuthorizeResponse(
                "APPROVED", acquirerReference, authorizationCode, "00", "Approved");
    }

    public static AuthorizeResponse businessDecline(String declineCode, String message) {
        return new AuthorizeResponse("DECLINED_BUSINESS", null, null, declineCode, message);
    }

    public static AuthorizeResponse technicalFailure(String code, String message) {
        return new AuthorizeResponse("DECLINED_TECHNICAL", null, null, code, message);
    }
}
