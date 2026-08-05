package dev.maestro.router.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.events.payload.CaptureRequested;
import dev.maestro.events.payload.RefundRequested;
import dev.maestro.events.payload.VoidRequested;
import dev.maestro.router.operation.AcquirerOperationService;
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
            case EventTypes.AUTHORIZATION_REQUESTED -> operations.authorize(
                    codec.deserialize(message, AuthorizationRequested.class));
            case EventTypes.CAPTURE_REQUESTED -> operations.capture(
                    codec.deserialize(message, CaptureRequested.class));
            case EventTypes.REFUND_REQUESTED -> operations.refund(
                    codec.deserialize(message, RefundRequested.class));
            case EventTypes.VOID_REQUESTED -> operations.voidAuthorization(
                    codec.deserialize(message, VoidRequested.class));
            // Skipped rather than fatal, so a new command type can be introduced before
            // every consumer understands it.
            default -> log.debug("Ignoring command of type {}", eventType);
        }
    }
}
