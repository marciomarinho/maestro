package dev.maestro.payment.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AttemptRecorded;
import dev.maestro.events.payload.AuthorizationDeclined;
import dev.maestro.events.payload.AuthorizationFailed;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.events.payload.CaptureFailed;
import dev.maestro.events.payload.CaptureSucceeded;
import dev.maestro.events.payload.RefundFailed;
import dev.maestro.events.payload.RefundSucceeded;
import dev.maestro.events.payload.VoidFailed;
import dev.maestro.events.payload.VoidSucceeded;
import dev.maestro.payment.attempt.AttemptProjection;
import dev.maestro.payment.core.PaymentLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Routes acquirer outcomes to the state machine.
 *
 * <p>Offsets are committed after the listener returns, so a crash mid-processing
 * redelivers rather than loses. Redelivery is harmless because every transition
 * downstream is guarded.
 */
@Component
public class PaymentOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeListener.class);

    private final PaymentLifecycleService lifecycle;
    private final AttemptProjection attempts;
    private final EventCodec codec;

    public PaymentOutcomeListener(
            PaymentLifecycleService lifecycle, AttemptProjection attempts, EventCodec codec) {
        this.lifecycle = lifecycle;
        this.attempts = attempts;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "payment-api")
    public void onPaymentEvent(String message) {
        String eventType = codec.peekEventType(message);
        switch (eventType) {
            case EventTypes.AUTHORIZATION_SUCCEEDED -> lifecycle.onAuthorized(
                    codec.deserialize(message, AuthorizationSucceeded.class).payload());
            case EventTypes.AUTHORIZATION_DECLINED -> lifecycle.onDeclined(
                    codec.deserialize(message, AuthorizationDeclined.class).payload());
            case EventTypes.AUTHORIZATION_FAILED -> lifecycle.onAuthorizationFailed(
                    codec.deserialize(message, AuthorizationFailed.class).payload());
            case EventTypes.CAPTURE_SUCCEEDED -> lifecycle.onCaptured(
                    codec.deserialize(message, CaptureSucceeded.class).payload());
            case EventTypes.CAPTURE_FAILED -> lifecycle.onCaptureFailed(
                    codec.deserialize(message, CaptureFailed.class).payload());
            case EventTypes.VOID_SUCCEEDED -> lifecycle.onVoided(
                    codec.deserialize(message, VoidSucceeded.class).payload());
            case EventTypes.VOID_FAILED -> lifecycle.onVoidFailed(
                    codec.deserialize(message, VoidFailed.class).payload());
            case EventTypes.REFUND_SUCCEEDED -> lifecycle.onRefunded(
                    codec.deserialize(message, RefundSucceeded.class).payload());
            case EventTypes.REFUND_FAILED -> lifecycle.onRefundFailed(
                    codec.deserialize(message, RefundFailed.class).payload());
            // The only event here that changes no state. It is projected purely so the
            // routing decision can be explained back to the merchant (ADR-0017).
            case EventTypes.ATTEMPT_RECORDED -> attempts.apply(
                    codec.deserialize(message, AttemptRecorded.class).payload());
            // An unrecognised type is skipped rather than fatal, so a producer can
            // introduce an event before every consumer knows about it. The expiry event
            // this service publishes lands here too and is deliberately ignored — the
            // payment was already marked expired in the transaction that emitted it.
            default -> log.debug("Ignoring event of type {}", eventType);
        }
    }
}
