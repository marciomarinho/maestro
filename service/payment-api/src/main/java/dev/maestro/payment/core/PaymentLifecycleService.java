package dev.maestro.payment.core;

import dev.maestro.domain.payment.CaptureMethod;
import dev.maestro.events.payload.AuthorizationDeclined;
import dev.maestro.events.payload.AuthorizationFailed;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.events.payload.CaptureFailed;
import dev.maestro.events.payload.CaptureSucceeded;
import dev.maestro.events.payload.RefundFailed;
import dev.maestro.events.payload.RefundSucceeded;
import dev.maestro.events.payload.VoidFailed;
import dev.maestro.events.payload.VoidSucceeded;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies acquirer outcomes to the payment state machine.
 *
 * <p>Every transition here is a guarded conditional update, so this whole class is
 * idempotent without keeping a processed-events table: a redelivered event finds the
 * payment no longer in the state the transition requires and changes zero rows. That is
 * the mechanism by which at-least-once delivery produces exactly-once money effects
 * (ADR-0006), and it is why adding deduplication bookkeeping here would be redundant
 * machinery guarding something the database already guarantees.
 */
@Service
public class PaymentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleService.class);
    private static final int EXPIRY_BATCH_SIZE = 100;

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentCommands commands;

    public PaymentLifecycleService(
            PaymentRepository payments, RefundRepository refunds, PaymentCommands commands) {
        this.payments = payments;
        this.refunds = refunds;
        this.commands = commands;
    }

    /**
     * Records the authorization, then captures immediately if the merchant asked for
     * automatic capture.
     *
     * <p>The follow-on capture is triggered here rather than by the router because the API
     * owns the state machine; the router only executes acquirer operations. Both the
     * transition and the resulting command are part of this transaction, so a payment
     * cannot end up in {@code CAPTURING} with nothing on its way to capture it.
     */
    @Transactional
    public void onAuthorized(AuthorizationSucceeded event) {
        int updated = payments.markAuthorized(
                event.paymentId(),
                event.acquirerId(),
                event.acquirerReference(),
                event.authorizationCode());
        if (updated == 0) {
            log.debug("payment={} already past AUTHORIZING; authorization redelivery ignored",
                    event.paymentId());
            return;
        }
        log.info("payment={} status=AUTHORIZED acquirer={}", event.paymentId(), event.acquirerId());

        Payment payment = payments.findById(event.paymentId()).orElseThrow();
        if (payment.captureMethod() == CaptureMethod.AUTOMATIC
                && payments.transitionToCapturing(payment.id()) == 1) {
            commands.requestCapture(payment, payment.amountMinor());
            log.info("payment={} automatic capture requested", payment.id());
        }
    }

    @Transactional
    public void onDeclined(AuthorizationDeclined event) {
        logTransition("DECLINED", event.paymentId(), payments.markDeclined(
                event.paymentId(), event.acquirerId(), event.declineCode(), event.message()));
    }

    @Transactional
    public void onAuthorizationFailed(AuthorizationFailed event) {
        logTransition("FAILED", event.paymentId(), payments.markFailed(event.paymentId(), event.reason()));
    }

    @Transactional
    public void onCaptured(CaptureSucceeded event) {
        logTransition("CAPTURED", event.paymentId(),
                payments.markCaptured(event.paymentId(), event.amountMinor()));
    }

    /** A failed capture leaves the authorization intact, so the merchant can try again. */
    @Transactional
    public void onCaptureFailed(CaptureFailed event) {
        logTransition("AUTHORIZED (capture failed)", event.paymentId(),
                payments.markCaptureFailed(event.paymentId(), event.reason()));
    }

    @Transactional
    public void onVoided(VoidSucceeded event) {
        logTransition("VOIDED", event.paymentId(), payments.markVoided(event.paymentId()));
    }

    /**
     * A failed void leaves the payment authorized.
     *
     * <p>Nothing is recorded beyond the log line: the hold is still real, and it will lapse
     * on its own at expiry. Marking the payment as anything else would tell the merchant
     * the money was released when the issuer still has it.
     */
    @Transactional
    public void onVoidFailed(VoidFailed event) {
        log.warn("payment={} void failed, authorization remains: {}",
                event.paymentId(), event.reason());
    }

    @Transactional
    public void onRefunded(RefundSucceeded event) {
        if (refunds.markSucceeded(event.refundId(), event.acquirerReference()) == 0) {
            log.debug("refund={} already settled; redelivery ignored", event.refundId());
            return;
        }
        payments.settleRefund(event.paymentId(), event.amountMinor());
        log.info("payment={} refund={} settled", event.paymentId(), event.refundId());
    }

    /**
     * Releases the reservation a failed refund was holding.
     *
     * <p>Without this, money that was never returned would permanently consume part of the
     * refundable balance, and a merchant retrying a failed refund would be told the payment
     * had already been fully refunded.
     */
    @Transactional
    public void onRefundFailed(RefundFailed event) {
        if (refunds.markFailed(event.refundId(), event.reason()) == 0) {
            log.debug("refund={} already terminal; redelivery ignored", event.refundId());
            return;
        }
        payments.releaseRefundReservation(event.paymentId(), event.amountMinor());
        log.warn("payment={} refund={} failed, reservation released: {}",
                event.paymentId(), event.refundId(), event.reason());
    }

    /**
     * Sweeps authorizations that have lapsed.
     *
     * <p>Claimed with {@code FOR UPDATE SKIP LOCKED} so every instance can sweep at once
     * without a leader election. Each expiry announces itself so the ledger can release its
     * hold; the transition and the announcement share this transaction.
     *
     * @return how many authorizations were expired
     */
    @Transactional
    public int expireLapsedAuthorizations() {
        List<Payment> lapsed = payments.claimExpiredAuthorizations(EXPIRY_BATCH_SIZE);
        int expired = 0;
        for (Payment payment : lapsed) {
            if (payments.markExpired(payment.id()) == 1) {
                commands.announceExpiry(payment);
                expired++;
                log.info("payment={} authorization expired", payment.id());
            }
        }
        return expired;
    }

    private static void logTransition(String outcome, String paymentId, int rowsUpdated) {
        if (rowsUpdated == 0) {
            log.debug("payment={} not in the expected state; {} event absorbed as a duplicate",
                    paymentId, outcome);
        } else {
            log.info("payment={} status={}", paymentId, outcome);
        }
    }
}
