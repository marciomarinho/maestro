package dev.maestro.events;

/**
 * Event type discriminators.
 *
 * <p>These strings are a published contract. Renaming one is a breaking change for
 * every consumer and for merchants receiving webhooks, so they are constants rather
 * than an enum — an unrecognised type must be skippable by an old consumer, not a
 * deserialisation failure.
 *
 * <p>Every event in both groups is keyed by <em>payment</em>, including refunds. A refund
 * must never be processed before the capture it reverses, and per-payment ordering is
 * what guarantees that (ADR-0005).
 */
public final class EventTypes {

    // Commands: an instruction that something should happen.
    public static final String AUTHORIZATION_REQUESTED = "payment.authorization_requested";
    public static final String CAPTURE_REQUESTED = "payment.capture_requested";
    public static final String VOID_REQUESTED = "payment.void_requested";
    public static final String REFUND_REQUESTED = "payment.refund_requested";

    // Events: a statement that something did happen.
    public static final String AUTHORIZATION_SUCCEEDED = "payment.authorization_succeeded";
    public static final String AUTHORIZATION_DECLINED = "payment.authorization_declined";
    public static final String AUTHORIZATION_FAILED = "payment.authorization_failed";
    public static final String AUTHORIZATION_EXPIRED = "payment.authorization_expired";
    public static final String CAPTURE_SUCCEEDED = "payment.capture_succeeded";
    public static final String CAPTURE_FAILED = "payment.capture_failed";
    public static final String VOID_SUCCEEDED = "payment.void_succeeded";
    public static final String VOID_FAILED = "payment.void_failed";
    public static final String REFUND_SUCCEEDED = "payment.refund_succeeded";
    public static final String REFUND_FAILED = "payment.refund_failed";

    /**
     * One acquirer call was made and answered.
     *
     * <p>Not a lifecycle fact — nothing transitions because of it. It exists so the
     * routing decision can be explained to the merchant who paid for it, including the
     * attempts that failed on the way to the one that worked.
     */
    public static final String ATTEMPT_RECORDED = "payment.attempt_recorded";

    private EventTypes() {
    }
}
