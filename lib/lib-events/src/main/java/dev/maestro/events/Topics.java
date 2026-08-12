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

    /** Events that exhausted their retries, retained for operator redrive. */
    public static final String PAYMENT_EVENTS_DLQ = "maestro.payment.events.dlq.v1";

    /**
     * The dead-letter topic for a topic: {@code maestro.payment.commands.v1} becomes
     * {@code maestro.payment.commands.dlq.v1}. The version suffix stays outermost so a
     * v2 rollout carries its dead letters beside it rather than mixing them with v1's.
     */
    public static String dlqFor(String topic) {
        int version = topic.lastIndexOf(".v");
        return version < 0
                ? topic + ".dlq"
                : topic.substring(0, version) + ".dlq" + topic.substring(version);
    }

    private Topics() {
    }
}
