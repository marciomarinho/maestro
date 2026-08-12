package dev.maestro.observability;

/**
 * Tag keys used across the platform's metrics.
 *
 * <p>Shared for the same reason as {@link MetricNames}: a dashboard that groups by
 * {@code acquirer} breaks silently the day one service spells it {@code acquirer_id}.
 * Tag <em>values</em> must be low-cardinality — an identifier such as a payment id in a
 * tag turns every payment into its own time series and takes Prometheus down slowly.
 */
public final class MetricTags {

    /** Acquirer identifier, e.g. {@code southcross}. */
    public static final String ACQUIRER = "acquirer";
    /** Card network and currency, e.g. {@code VISA:AUD}. */
    public static final String CORRIDOR = "corridor";
    /** Payment operation: {@code authorize}, {@code capture}, {@code refund}, {@code void}. */
    public static final String OPERATION = "operation";
    /** Attempt outcome: {@code approved}, {@code declined}, {@code technical_failure}, {@code timeout}. */
    public static final String OUTCOME = "outcome";
    /** Payment state transition, e.g. {@code authorized}, {@code captured}. */
    public static final String TRANSITION = "transition";
    /** Kafka topic, on outbox and dead-letter metrics. */
    public static final String TOPIC = "topic";

    private MetricTags() {
    }
}
