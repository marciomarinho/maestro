package dev.maestro.domain.acquirer;

/**
 * Normalised issuer decline reasons.
 *
 * <p>Acquirers report declines with their own codes; each acquirer integration maps
 * them onto this set, so routing logic never branches on a vendor-specific string.
 * Every value here represents an answer from the issuer and is therefore
 * {@linkplain AcquirerOutcome.BusinessDecline final} — none may be re-attempted on
 * another acquirer (ADR-0012).
 *
 * <p>{@link #retryLaterPermitted} distinguishes conditions that may genuinely change
 * — a balance can be topped up — from those that cannot. It governs whether a
 * <em>merchant</em> may reasonably create a new payment later; it never authorises
 * the router to retry anything now.
 */
public enum DeclineCode {

    /** The account lacks the funds. May succeed later. */
    INSUFFICIENT_FUNDS(true),
    /** Beyond a velocity or amount limit. May succeed later. */
    LIMIT_EXCEEDED(true),
    /** The issuer refused without stating a reason. Ambiguous, and deliberately not retried. */
    DO_NOT_HONOUR(true),
    /** The card is past its expiry date. */
    EXPIRED_CARD(false),
    /** The card details do not correspond to a real card. */
    INVALID_CARD(false),
    /** The account is closed or the card withdrawn. */
    ACCOUNT_CLOSED(false),
    /** The issuer's fraud systems refused. Never re-present, through any channel. */
    SUSPECTED_FRAUD(false),
    /** The card is reported stolen. Never re-present. */
    STOLEN_CARD(false),
    /** The card is blocked for this transaction type or region. */
    RESTRICTED_CARD(false);

    private final boolean retryLaterPermitted;

    DeclineCode(boolean retryLaterPermitted) {
        this.retryLaterPermitted = retryLaterPermitted;
    }

    /**
     * Whether the underlying condition can change, so that a merchant creating a new
     * payment later is reasonable. Never a licence for the router to retry now.
     */
    public boolean retryLaterPermitted() {
        return retryLaterPermitted;
    }
}
