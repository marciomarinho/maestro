package dev.maestro.domain.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The payment state machine.
 *
 * <p>Transitions are declared here and enforced at the database with guarded
 * conditional updates ({@code WHERE status = ?}), so a duplicate event affects zero
 * rows rather than repeating an effect (ADR-0006). This enum is the single
 * description of what is legal; the persistence layer refuses anything else.
 *
 * <p>Two distinctions carry weight. {@link #DECLINED} is the issuer's answer and is
 * final; {@link #FAILED} is the platform's inability to obtain an answer, and is the
 * only case in which another acquirer may be tried (ADR-0012). And settlement is
 * deliberately absent — whether captured funds have settled is a property of the
 * ledger, not of the payment.
 */
public enum PaymentStatus {

    /** Created but not yet submitted for authorization. */
    CREATED,
    /** Authorization is in flight at an acquirer. */
    AUTHORIZING,
    /** Funds are reserved. No money has moved. */
    AUTHORIZED,
    /** The issuer refused. Final. */
    DECLINED,
    /** No answer could be obtained from any acquirer. Final. */
    FAILED,
    /** Capture is in flight at an acquirer. */
    CAPTURING,
    /** Funds have been taken. */
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    /** The authorization was released before capture. Final. */
    VOIDED,
    /** The authorization hold lapsed before capture. Final. */
    EXPIRED,
    /** Cancelled before it was ever submitted. Final. */
    CANCELLED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(AUTHORIZING, CANCELLED)),
            Map.entry(AUTHORIZING, EnumSet.of(AUTHORIZED, DECLINED, FAILED)),
            Map.entry(AUTHORIZED, EnumSet.of(CAPTURING, VOIDED, EXPIRED)),
            Map.entry(CAPTURING, EnumSet.of(CAPTURED, AUTHORIZED)),
            Map.entry(CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED)),
            Map.entry(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED)),
            Map.entry(REFUNDED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(DECLINED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(FAILED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(VOIDED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(EXPIRED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(PaymentStatus.class)));

    public boolean canTransitionTo(PaymentStatus next) {
        return next != null && ALLOWED.get(this).contains(next);
    }

    public Set<PaymentStatus> allowedTransitions() {
        return Set.copyOf(ALLOWED.get(this));
    }

    /** No further transition is possible from a terminal state. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** True once the authorization exists, so an acquirer holds funds for this payment. */
    public boolean isAuthorized() {
        return this == AUTHORIZED || this == CAPTURING || this == CAPTURED
                || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
