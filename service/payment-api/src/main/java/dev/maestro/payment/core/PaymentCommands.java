package dev.maestro.payment.core;

import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationExpired;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.events.payload.CaptureRequested;
import dev.maestro.events.payload.RefundRequested;
import dev.maestro.events.payload.VoidRequested;
import dev.maestro.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/**
 * Appends payment commands to the outbox.
 *
 * <p>Shared by the merchant-facing service and the event listener, because a capture can
 * be requested either way — by the merchant explicitly, or automatically once an
 * authorization succeeds — and both must produce an identical instruction.
 *
 * <p>Every command is keyed by payment, so operations on one payment are strictly ordered
 * and a capture can never overtake the authorization it depends on (ADR-0005).
 */
@Component
public class PaymentCommands {

    private static final String AGGREGATE_TYPE = "payment";

    private final OutboxWriter outbox;

    public PaymentCommands(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void requestAuthorization(Payment payment) {
        append(
                payment,
                EventTypes.AUTHORIZATION_REQUESTED,
                Topics.PAYMENT_COMMANDS,
                new AuthorizationRequested(
                        payment.id(),
                        payment.merchantId(),
                        payment.amountMinor(),
                        payment.currency(),
                        payment.cardToken(),
                        payment.cardNetwork(),
                        payment.captureMethod().name()));
    }

    public void requestCapture(Payment payment, long amountMinor) {
        append(
                payment,
                EventTypes.CAPTURE_REQUESTED,
                Topics.PAYMENT_COMMANDS,
                new CaptureRequested(
                        payment.id(),
                        payment.merchantId(),
                        amountMinor,
                        payment.currency(),
                        payment.acquirerId(),
                        payment.acquirerReference()));
    }

    public void requestVoid(Payment payment) {
        append(
                payment,
                EventTypes.VOID_REQUESTED,
                Topics.PAYMENT_COMMANDS,
                new VoidRequested(
                        payment.id(),
                        payment.merchantId(),
                        payment.acquirerId(),
                        payment.acquirerReference()));
    }

    public void requestRefund(Payment payment, RefundRepository.Refund refund) {
        append(
                payment,
                EventTypes.REFUND_REQUESTED,
                Topics.PAYMENT_COMMANDS,
                new RefundRequested(
                        refund.id(),
                        payment.id(),
                        payment.merchantId(),
                        refund.amountMinor(),
                        refund.currency(),
                        payment.acquirerId(),
                        payment.acquirerReference(),
                        refund.reason()));
    }

    /**
     * Announces that an authorization lapsed.
     *
     * <p>Published to the events topic rather than the commands topic: nobody is being
     * asked to do anything at the acquirer — the hold has already gone — and the ledger
     * simply needs to know.
     */
    public void announceExpiry(Payment payment) {
        append(
                payment,
                EventTypes.AUTHORIZATION_EXPIRED,
                Topics.PAYMENT_EVENTS,
                new AuthorizationExpired(
                        payment.id(),
                        payment.merchantId(),
                        payment.amountMinor(),
                        payment.currency()));
    }

    private void append(Payment payment, String eventType, String topic, Object payload) {
        outbox.append(
                EventEnvelope.of(eventType, payment.merchantId(), payment.id(), payload),
                AGGREGATE_TYPE,
                topic);
    }
}
