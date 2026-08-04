package dev.maestro.router.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.router.authorization.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes payment commands.
 *
 * <p>Records are keyed by payment, so every command for one payment arrives on one
 * partition in order — a capture can never be processed before the authorization it
 * depends on (ADR-0005). Deliberately <em>not</em> transactional: the acquirer call
 * inside must not hold a database transaction open across the network.
 */
@Component
public class PaymentCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

    private final AuthorizationService authorizations;
    private final EventCodec codec;

    public PaymentCommandListener(AuthorizationService authorizations, EventCodec codec) {
        this.authorizations = authorizations;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "router")
    public void onCommand(String message) {
        String eventType = codec.peekEventType(message);
        switch (eventType) {
            case EventTypes.AUTHORIZATION_REQUESTED ->
                    authorizations.authorize(
                            codec.deserialize(message, AuthorizationRequested.class));
            // Skipped rather than fatal, so a new command type can be introduced before
            // every consumer understands it.
            default -> log.debug("Ignoring command of type {}", eventType);
        }
    }
}
