package dev.maestro.payment.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationDeclined;
import dev.maestro.events.payload.AuthorizationFailed;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.payment.core.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies acquirer outcomes to the payment state machine.
 *
 * <p>This consumer keeps <em>no</em> deduplication table, deliberately. Its only effect
 * is a guarded conditional update, which is already idempotent: a redelivered event
 * finds the payment no longer in {@code AUTHORIZING} and changes zero rows. Adding a
 * processed-events table here would be redundant machinery guarding something the
 * database already guarantees (ADR-0006).
 *
 * <p>Offsets are committed after the listener returns (record acknowledgement mode), so
 * a crash mid-processing redelivers rather than loses.
 */
@Component
public class PaymentOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeListener.class);

    private final PaymentRepository payments;
    private final EventCodec codec;

    public PaymentOutcomeListener(PaymentRepository payments, EventCodec codec) {
        this.payments = payments;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "payment-api")
    @Transactional
    public void onPaymentEvent(String message) {
        String eventType = codec.peekEventType(message);
        switch (eventType) {
            case EventTypes.AUTHORIZATION_SUCCEEDED -> applyAuthorized(message);
            case EventTypes.AUTHORIZATION_DECLINED -> applyDeclined(message);
            case EventTypes.AUTHORIZATION_FAILED -> applyFailed(message);
            // An unrecognised type is skipped rather than fatal, so a producer can
            // introduce an event before every consumer knows about it.
            default -> log.debug("Ignoring event of type {}", eventType);
        }
    }

    private void applyAuthorized(String message) {
        AuthorizationSucceeded event =
                codec.deserialize(message, AuthorizationSucceeded.class).payload();
        int updated = payments.markAuthorized(
                event.paymentId(),
                event.acquirerId(),
                event.acquirerReference(),
                event.authorizationCode());
        logOutcome("AUTHORIZED", event.paymentId(), updated);
    }

    private void applyDeclined(String message) {
        AuthorizationDeclined event =
                codec.deserialize(message, AuthorizationDeclined.class).payload();
        int updated = payments.markDeclined(
                event.paymentId(), event.acquirerId(), event.declineCode(), event.message());
        logOutcome("DECLINED", event.paymentId(), updated);
    }

    private void applyFailed(String message) {
        AuthorizationFailed event = codec.deserialize(message, AuthorizationFailed.class).payload();
        int updated = payments.markFailed(event.paymentId(), event.reason());
        logOutcome("FAILED", event.paymentId(), updated);
    }

    private static void logOutcome(String outcome, String paymentId, int rowsUpdated) {
        if (rowsUpdated == 0) {
            log.debug(
                    "payment={} already past AUTHORIZING; {} event absorbed as a duplicate",
                    paymentId,
                    outcome);
        } else {
            log.info("payment={} status={}", paymentId, outcome);
        }
    }
}
