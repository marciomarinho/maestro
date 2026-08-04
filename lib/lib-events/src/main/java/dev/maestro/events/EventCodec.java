package dev.maestro.events;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises and deserialises {@link EventEnvelope}s.
 *
 * <p>The codec owns its own {@link ObjectMapper} rather than borrowing the
 * application's. The event format is a contract between services and with merchants;
 * it must not change because someone adjusted an unrelated HTTP serialisation
 * setting. Unknown properties are ignored on read so that a producer can add a field
 * without breaking consumers that have not been redeployed.
 */
public final class EventCodec {

    private final ObjectMapper mapper;

    public EventCodec() {
        // Jackson 3 writes java.time values as ISO-8601 by default, so timestamps need
        // no explicit configuration here — only the naming strategy and the forward
        // compatibility setting are contractual.
        this.mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public String serialize(EventEnvelope<?> envelope) {
        return mapper.writeValueAsString(envelope);
    }

    public <T> EventEnvelope<T> deserialize(String json, Class<T> payloadType) {
        return mapper.readValue(
                json,
                mapper.getTypeFactory()
                        .constructParametricType(EventEnvelope.class, payloadType));
    }

    /**
     * Reads only the {@code event_type} header.
     *
     * <p>A consumer uses this to decide whether it cares about a record before paying
     * to deserialise a payload it may not even have a class for — which is what lets
     * an old consumer skip a new event type instead of failing on it.
     */
    public String peekEventType(String json) {
        return mapper.readTree(json).path("event_type").asString();
    }

    /** Exposed for services that need to store an envelope payload as JSON. */
    public String toJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T fromJson(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
