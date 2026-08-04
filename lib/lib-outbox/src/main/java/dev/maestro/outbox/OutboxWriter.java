package dev.maestro.outbox;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Appends an event to the outbox <em>inside the caller's transaction</em>.
 *
 * <p>This is the whole point of the pattern (ADR-0004): the state change and the
 * intent to publish commit together or not at all. There is no window in which a
 * payment moved to {@code AUTHORIZING} but the instruction to authorize it was lost,
 * and none in which the router authorizes a payment the database has no record of.
 *
 * <p>Callers must already be in a transaction. Appending outside one would defeat the
 * mechanism silently, so it is rejected rather than tolerated.
 */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final EventCodec codec;
    private final OutboxProperties properties;
    private final Runnable relayWakeUp;

    public OutboxWriter(
            JdbcClient jdbc,
            EventCodec codec,
            OutboxProperties properties,
            Runnable relayWakeUp) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.properties = properties;
        this.relayWakeUp = relayWakeUp;
    }

    public void append(EventEnvelope<?> envelope, String aggregateType, String topic) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox writes must join the transaction that performs the state change; "
                            + "appending outside a transaction would silently defeat the pattern");
        }

        jdbc.sql("""
                INSERT INTO %s.outbox_event
                    (id, aggregate_type, aggregate_id, topic, event_type, payload, trace_parent)
                VALUES (:id, :aggregateType, :aggregateId, :topic, :eventType, :payload, :traceParent)
                """.formatted(properties.schema()))
                .param("id", envelope.eventId())
                .param("aggregateType", aggregateType)
                .param("aggregateId", envelope.aggregateId())
                .param("topic", topic)
                .param("eventType", envelope.eventType())
                .param("payload", codec.serialize(envelope))
                .param("traceParent", envelope.traceParent())
                .update();

        wakeRelayAfterCommit();
    }

    /**
     * Nudges the relay once the transaction commits, so publication latency is
     * governed by the commit rather than by the polling interval. Polling remains the
     * safety net for anything this misses — a crash before the callback runs, or a
     * row written by another instance.
     */
    private void wakeRelayAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relayWakeUp.run();
            }
        });
    }
}
