package dev.maestro.events;

/**
 * Kafka record headers that accompany every published envelope.
 *
 * <p>Headers carry what the transport needs to see without deserialising the payload.
 * The envelope's JSON also holds {@code traceParent} for humans reading a raw record;
 * the header is the copy the tracing instrumentation actually reads, because framework
 * extraction happens before any application code sees the message.
 */
public final class EventHeaders {

    /** W3C trace context ({@code traceparent}), restored from the outbox row by the relay. */
    public static final String TRACE_PARENT = "traceparent";

    private EventHeaders() {
    }
}
