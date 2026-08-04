package dev.maestro.events;

import dev.maestro.domain.id.Ids;
import java.time.Instant;
import java.util.Objects;

/**
 * The wire contract for everything published to Kafka.
 *
 * <p>Every event carries the same header set so that consumers can deduplicate,
 * order, scope and trace without understanding the payload. In particular
 * {@code eventId} is the key every consumer deduplicates on, which is what turns
 * at-least-once delivery into exactly-once effects (ADR-0006).
 *
 * @param eventId       unique per event; the deduplication key
 * @param eventType     stable type discriminator, e.g. {@code payment.authorization_requested}
 * @param schemaVersion incremented only on a breaking payload change
 * @param occurredAt    when the fact happened, not when it was published
 * @param merchantId    tenant scope, present on every event
 * @param aggregateId   the entity this concerns; also the Kafka partition key
 * @param traceParent   W3C trace context, so the asynchronous hop stays in one trace
 * @param payload       the type-specific body
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String merchantId,
        String aggregateId,
        String traceParent,
        T payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
    }

    /** Creates an envelope stamped now, with a fresh event identifier. */
    public static <T> EventEnvelope<T> of(
            String eventType, String merchantId, String aggregateId, T payload) {
        return new EventEnvelope<>(
                Ids.event(),
                eventType,
                CURRENT_SCHEMA_VERSION,
                Instant.now(),
                merchantId,
                aggregateId,
                null,
                payload);
    }

    public EventEnvelope<T> withTraceParent(String newTraceParent) {
        return new EventEnvelope<>(
                eventId, eventType, schemaVersion, occurredAt,
                merchantId, aggregateId, newTraceParent, payload);
    }
}
