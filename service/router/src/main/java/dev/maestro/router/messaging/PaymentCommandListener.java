package dev.maestro.router.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.events.payload.CaptureRequested;
import dev.maestro.events.payload.RefundRequested;
import dev.maestro.events.payload.VoidRequested;
import dev.maestro.observability.LogContext;
import dev.maestro.router.operation.AcquirerOperationService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes payment commands.
 *
 * <p>Records are keyed by payment, so every command for one payment arrives on one
 * partition in order — a capture can never be processed before the authorization it
 * depends on, and a refund never before its capture (ADR-0005).
 *
 * <p>Deliberately not transactional: the acquirer call inside must not hold a database
 * transaction open across the network.
 */
@Component
public class PaymentCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

    private final AcquirerOperationService operations;
    private final EventCodec codec;

    public PaymentCommandListener(AcquirerOperationService operations, EventCodec codec) {
        this.operations = operations;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "router")
    public void onCommand(String message) {
        String eventType = codec.peekEventType(message);
        switch (eventType) {
            case EventTypes.AUTHORIZATION_REQUESTED -> handle(
                    codec.deserialize(message, AuthorizationRequested.class), operations::authorize);
            case EventTypes.CAPTURE_REQUESTED -> handle(
                    codec.deserialize(message, CaptureRequested.class), operations::capture);
            case EventTypes.REFUND_REQUESTED -> handle(
                    codec.deserialize(message, RefundRequested.class), operations::refund);
            case EventTypes.VOID_REQUESTED -> handle(
                    codec.deserialize(message, VoidRequested.class), operations::voidAuthorization);
            // Skipped rather than fatal, so a new command type can be introduced before
            // every consumer understands it.
            default -> log.debug("Ignoring command of type {}", eventType);
        }
    }

    /** Everything logged while handling this command carries the payment's identifiers. */
    @SuppressWarnings("try") // the resource exists only for its close()
    private <T> void handle(EventEnvelope<T> envelope, Consumer<EventEnvelope<T>> handler) {
        try (var ignored = LogContext.forPayment(envelope.aggregateId(), envelope.merchantId())) {
            handler.accept(envelope);
        }
    }
}
