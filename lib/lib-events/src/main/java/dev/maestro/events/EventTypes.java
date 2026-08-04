package dev.maestro.events;

/**
 * Event type discriminators.
 *
 * <p>These strings are a published contract. Renaming one is a breaking change for
 * every consumer and for merchants receiving webhooks, so they are constants rather
 * than an enum — an unrecognised type must be skippable by an old consumer, not a
 * deserialisation failure.
 */
public final class EventTypes {

    // Commands: an instruction that something should happen.
    public static final String AUTHORIZATION_REQUESTED = "payment.authorization_requested";
    public static final String CAPTURE_REQUESTED = "payment.capture_requested";

    // Events: a statement that something did happen.
    public static final String AUTHORIZATION_SUCCEEDED = "payment.authorization_succeeded";
    public static final String AUTHORIZATION_DECLINED = "payment.authorization_declined";
    public static final String AUTHORIZATION_FAILED = "payment.authorization_failed";

    private EventTypes() {
    }
}
