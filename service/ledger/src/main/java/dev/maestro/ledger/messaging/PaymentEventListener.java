package dev.maestro.ledger.messaging;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationExpired;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.events.payload.CaptureSucceeded;
import dev.maestro.events.payload.RefundSucceeded;
import dev.maestro.events.payload.VoidSucceeded;
import dev.maestro.observability.LogContext;
import dev.maestro.ledger.posting.LedgerPostingService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds payment outcomes into the books.
 *
 * <p>The ledger keeps no deduplication table. Postings are guarded by the unique
 * constraint on {@code journal_transaction.source_event_id} and holds by their primary
 * key, so a redelivered event is absorbed by the database rather than by bookkeeping in
 * the application (ADR-0006).
 *
 * <p>Only the succeeded events matter here. A declined authorization or a failed capture
 * moved no money and has nothing to record — the payment's own status is where that lives.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final LedgerPostingService postings;
    private final EventCodec codec;

    public PaymentEventListener(LedgerPostingService postings, EventCodec codec) {
        this.postings = postings;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "ledger")
    public void onPaymentEvent(String message) {
        String eventType = codec.peekEventType(message);
        switch (eventType) {
            case EventTypes.AUTHORIZATION_SUCCEEDED -> handle(
                    codec.deserialize(message, AuthorizationSucceeded.class), postings::onAuthorized);
            case EventTypes.CAPTURE_SUCCEEDED -> handle(
                    codec.deserialize(message, CaptureSucceeded.class), postings::onCaptured);
            case EventTypes.REFUND_SUCCEEDED -> handle(
                    codec.deserialize(message, RefundSucceeded.class), postings::onRefunded);
            case EventTypes.VOID_SUCCEEDED -> handle(
                    codec.deserialize(message, VoidSucceeded.class), postings::onVoided);
            case EventTypes.AUTHORIZATION_EXPIRED -> handle(
                    codec.deserialize(message, AuthorizationExpired.class), postings::onExpired);
            default -> log.debug("No ledger effect for event type {}", eventType);
        }
    }

    /** Everything logged while posting this event carries the payment's identifiers. */
    @SuppressWarnings("try") // the resource exists only for its close()
    private <T> void handle(EventEnvelope<T> envelope, Consumer<EventEnvelope<T>> handler) {
        try (var ignored = LogContext.forPayment(envelope.aggregateId(), envelope.merchantId())) {
            handler.accept(envelope);
        }
    }
}
