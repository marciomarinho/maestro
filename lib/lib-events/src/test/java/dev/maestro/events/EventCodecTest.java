package dev.maestro.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.maestro.events.payload.AuthorizationRequested;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventCodecTest {

    private final EventCodec codec = new EventCodec();

    private static EventEnvelope<AuthorizationRequested> sampleEnvelope() {
        return EventEnvelope.of(
                EventTypes.AUTHORIZATION_REQUESTED,
                "mch_test",
                "pay_test",
                new AuthorizationRequested(
                        "pay_test", "mch_test", 1999L, "AUD", "tok_visa_ok", "VISA", "AUTOMATIC"));
    }

    @Test
    void roundTripsAnEnvelope() {
        EventEnvelope<AuthorizationRequested> original = sampleEnvelope();

        EventEnvelope<AuthorizationRequested> restored =
                codec.deserialize(codec.serialize(original), AuthorizationRequested.class);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.eventType()).isEqualTo(EventTypes.AUTHORIZATION_REQUESTED);
        assertThat(restored.payload()).isEqualTo(original.payload());
    }

    @Test
    @DisplayName("the wire format is snake_case, independent of the application's Jackson config")
    void wireFormatIsSnakeCase() {
        String json = codec.serialize(sampleEnvelope());

        assertThat(json)
                .contains("\"event_id\"")
                .contains("\"event_type\"")
                .contains("\"schema_version\"")
                .contains("\"occurred_at\"")
                .contains("\"amount_minor\"")
                .contains("\"card_token\"")
                .doesNotContain("\"eventId\"")
                .doesNotContain("\"amountMinor\"");
    }

    @Test
    void timestampsSerialiseAsIso8601RatherThanEpochNumbers() {
        String json = codec.serialize(sampleEnvelope());

        assertThat(json).containsPattern("\"occurred_at\":\"\\d{4}-\\d{2}-\\d{2}T");
    }

    @Test
    void instantSurvivesTheRoundTripToMillisecondPrecision() {
        Instant occurredAt = Instant.parse("2026-08-04T09:41:12.402Z");
        EventEnvelope<AuthorizationRequested> original = new EventEnvelope<>(
                "evt_1", EventTypes.AUTHORIZATION_REQUESTED, 1, occurredAt,
                "mch_test", "pay_test", null, sampleEnvelope().payload());

        EventEnvelope<AuthorizationRequested> restored =
                codec.deserialize(codec.serialize(original), AuthorizationRequested.class);

        assertThat(restored.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("a consumer can read the type without deserialising a payload it may not know")
    void peeksTheEventTypeWithoutTheFullPayload() {
        String json = codec.serialize(sampleEnvelope());

        assertThat(codec.peekEventType(json)).isEqualTo(EventTypes.AUTHORIZATION_REQUESTED);
    }

    @Test
    @DisplayName("an added producer field does not break a consumer that has not been redeployed")
    void unknownFieldsAreIgnoredOnRead() {
        String jsonWithNewField = codec.serialize(sampleEnvelope())
                .replaceFirst("\\{", "{\"a_field_added_later\":\"value\",");

        EventEnvelope<AuthorizationRequested> restored =
                codec.deserialize(jsonWithNewField, AuthorizationRequested.class);

        assertThat(restored.payload().paymentId()).isEqualTo("pay_test");
    }

    @Test
    void traceContextIsCarriedOnTheEnvelope() {
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        EventEnvelope<AuthorizationRequested> withTrace =
                sampleEnvelope().withTraceParent(traceParent);

        assertThat(codec.deserialize(codec.serialize(withTrace), AuthorizationRequested.class)
                        .traceParent())
                .isEqualTo(traceParent);
    }
}
