package dev.maestro.events;

/**
 * Kafka topics, with the version in the name so an incompatible schema change can be
 * rolled out alongside the old one rather than in place.
 *
 * <p>Every topic here is keyed by {@code payment_id}. Ordering is required per
 * payment — a capture must never be processed before its authorization — and only
 * per payment; merchant-level fairness is a scheduling concern that deliberately does
 * not live in the partition key (ADR-0005).
 */
public final class Topics {

    /** Instructions for the router: authorize, capture, refund, void. */
    public static final String PAYMENT_COMMANDS = "maestro.payment.commands.v1";

    /** Outcomes and lifecycle facts, consumed by payment-api and the ledger. */
    public static final String PAYMENT_EVENTS = "maestro.payment.events.v1";

    /** Commands that exhausted their retries, retained for operator redrive. */
    public static final String PAYMENT_COMMANDS_DLQ = "maestro.payment.commands.dlq.v1";

    private Topics() {
    }
}
