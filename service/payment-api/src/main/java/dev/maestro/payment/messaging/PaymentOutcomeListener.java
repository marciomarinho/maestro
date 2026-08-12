package dev.maestro.payment.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventEnvelope;
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
import dev.maestro.observability.LogContext;
import dev.maestro.payment.attempt.AttemptProjection;
import dev.maestro.payment.core.PaymentLifecycleService;
import java.util.function.Consumer;
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
            case EventTypes.AUTHORIZATION_SUCCEEDED -> handle(
                    codec.deserialize(message, AuthorizationSucceeded.class), lifecycle::onAuthorized);
            case EventTypes.AUTHORIZATION_DECLINED -> handle(
                    codec.deserialize(message, AuthorizationDeclined.class), lifecycle::onDeclined);
            case EventTypes.AUTHORIZATION_FAILED -> handle(
                    codec.deserialize(message, AuthorizationFailed.class),
                    lifecycle::onAuthorizationFailed);
            case EventTypes.CAPTURE_SUCCEEDED -> handle(
                    codec.deserialize(message, CaptureSucceeded.class), lifecycle::onCaptured);
            case EventTypes.CAPTURE_FAILED -> handle(
                    codec.deserialize(message, CaptureFailed.class), lifecycle::onCaptureFailed);
            case EventTypes.VOID_SUCCEEDED -> handle(
                    codec.deserialize(message, VoidSucceeded.class), lifecycle::onVoided);
            case EventTypes.VOID_FAILED -> handle(
                    codec.deserialize(message, VoidFailed.class), lifecycle::onVoidFailed);
            case EventTypes.REFUND_SUCCEEDED -> handle(
                    codec.deserialize(message, RefundSucceeded.class), lifecycle::onRefunded);
            case EventTypes.REFUND_FAILED -> handle(
                    codec.deserialize(message, RefundFailed.class), lifecycle::onRefundFailed);
            // The only event here that changes no state. It is projected purely so the
            // routing decision can be explained back to the merchant (ADR-0017).
            case EventTypes.ATTEMPT_RECORDED -> handle(
                    codec.deserialize(message, AttemptRecorded.class), attempts::apply);
            // An unrecognised type is skipped rather than fatal, so a producer can
            // introduce an event before every consumer knows about it. The expiry event
            // this service publishes lands here too and is deliberately ignored — the
            // payment was already marked expired in the transaction that emitted it.
            default -> log.debug("Ignoring event of type {}", eventType);
        }
    }

    /** Everything logged while applying this outcome carries the payment's identifiers. */
    @SuppressWarnings("try") // the resource exists only for its close()
    private <T> void handle(EventEnvelope<T> envelope, Consumer<T> handler) {
        try (var ignored = LogContext.forPayment(envelope.aggregateId(), envelope.merchantId())) {
            handler.accept(envelope.payload());
        }
    }
}
