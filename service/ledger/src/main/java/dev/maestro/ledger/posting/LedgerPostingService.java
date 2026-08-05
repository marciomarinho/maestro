package dev.maestro.ledger.posting;

import dev.maestro.domain.money.FeeCalculator;
import dev.maestro.domain.money.Money;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.payload.AuthorizationExpired;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.events.payload.CaptureSucceeded;
import dev.maestro.events.payload.RefundSucceeded;
import dev.maestro.events.payload.VoidSucceeded;
import dev.maestro.ledger.core.AccountRef;
import dev.maestro.ledger.core.JournalEntry;
import dev.maestro.ledger.core.LedgerQueries;
import dev.maestro.ledger.core.LedgerRepository;
import dev.maestro.ledger.fee.FeeScheduleRepository;
import dev.maestro.ledger.hold.HoldRepository;
import java.time.Duration;
import java.util.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns payment events into ledger effects.
 *
 * <p>The mapping is the one specified in {@code docs/domain.md} §4, and the shape of it is
 * the single most important modelling decision in the platform:
 *
 * <ul>
 *   <li><strong>Authorizations produce no postings.</strong> Nothing has moved. They create
 *       a hold. Recording an authorization as a posting inflates receivables and merchant
 *       balances with money nobody has, and every expiry then needs a compensating entry —
 *       which is how a ledger ends up carrying a permanent population of phantom amounts.
 *   <li><strong>Captures are where money moves</strong>, and where the fee is taken.
 *   <li><strong>Refunds are a movement in the opposite direction</strong>, not an undo, and
 *       they return the fee proportionally so a fully refunded payment nets to zero.
 * </ul>
 */
@Service
public class LedgerPostingService {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    /** Matches the window payment-api stamps on the payment when it authorizes. */
    private static final Duration AUTHORIZATION_LIFETIME = Duration.ofDays(7);

    private final LedgerRepository ledger;
    private final LedgerQueries queries;
    private final HoldRepository holds;
    private final FeeScheduleRepository feeSchedules;

    public LedgerPostingService(
            LedgerRepository ledger,
            LedgerQueries queries,
            HoldRepository holds,
            FeeScheduleRepository feeSchedules) {
        this.ledger = ledger;
        this.queries = queries;
        this.holds = holds;
        this.feeSchedules = feeSchedules;
    }

    /** Funds are reserved. A hold is created; nothing is posted. */
    @Transactional
    public void onAuthorized(EventEnvelope<AuthorizationSucceeded> envelope) {
        AuthorizationSucceeded event = envelope.payload();
        boolean placed = holds.place(
                event.paymentId(),
                event.merchantId(),
                event.acquirerId(),
                Money.of(event.amountMinor(), event.currency()),
                envelope.occurredAt().plus(AUTHORIZATION_LIFETIME));

        log.info(
                "payment={} hold={}",
                event.paymentId(),
                placed ? "placed" : "already present, redelivery ignored");
    }

    /**
     * Money moves.
     *
     * <pre>
     *   DR acquirer_receivable   gross    (the acquirer now owes us)
     *   CR merchant_payable      net      (we now owe the merchant)
     *   CR platform_fee_revenue  fee      (we earned this)
     * </pre>
     */
    @Transactional
    public void onCaptured(EventEnvelope<CaptureSucceeded> envelope) {
        CaptureSucceeded event = envelope.payload();
        Currency currency = Money.parseCurrency(event.currency());
        Money gross = Money.of(event.amountMinor(), currency);

        FeeScheduleRepository.FeeSchedule schedule =
                feeSchedules.forMerchant(event.merchantId(), currency);
        FeeCalculator.Fee split =
                FeeCalculator.calculate(gross, schedule.basisPoints(), schedule.fixedMinor());

        boolean recorded = ledger.record(JournalEntry
                .forEvent(envelope.eventId(), JournalEntry.TransactionType.CAPTURE, envelope.occurredAt())
                .payment(event.paymentId())
                .reference(event.acquirerReference())
                .debit(AccountRef.acquirerReceivable(event.acquirerId(), currency), gross)
                .credit(AccountRef.merchantPayable(event.merchantId(), currency), split.net())
                .credit(AccountRef.platformFeeRevenue(currency), split.fee())
                .build());

        if (recorded) {
            holds.consume(event.paymentId());
            log.info(
                    "payment={} captured gross={} fee={} net={}",
                    event.paymentId(),
                    gross.toDisplayString(),
                    split.fee().toDisplayString(),
                    split.net().toDisplayString());
        }
    }

    /**
     * Money moves back.
     *
     * <pre>
     *   DR merchant_payable      net returned   (we owe the merchant less)
     *   DR platform_fee_revenue  fee returned   (we give back our share)
     *   CR refund_clearing       total          (owed to the acquirer until settlement)
     * </pre>
     *
     * <p>The fee is returned in proportion to what was refunded, priced from what this
     * payment was actually charged rather than from today's schedule — a merchant whose
     * pricing changed after a capture must still be refunded on the original terms.
     */
    @Transactional
    public void onRefunded(EventEnvelope<RefundSucceeded> envelope) {
        RefundSucceeded event = envelope.payload();
        Currency currency = Money.parseCurrency(event.currency());
        Money refunded = Money.of(event.amountMinor(), currency);

        LedgerQueries.CaptureTotals captured = queries.captureTotals(event.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Refund for payment %s has no capture in the ledger; per-payment ordering "
                                .formatted(event.paymentId())
                                + "should make this impossible"));

        Money feeReturned =
                FeeCalculator.proportionalFeeRefund(captured.gross(), captured.fee(), refunded);
        Money netReturned = refunded.minus(feeReturned);

        boolean recorded = ledger.record(JournalEntry
                .forEvent(envelope.eventId(), JournalEntry.TransactionType.REFUND, envelope.occurredAt())
                .payment(event.paymentId())
                .reference(event.refundId())
                .debit(AccountRef.merchantPayable(event.merchantId(), currency), netReturned)
                .debit(AccountRef.platformFeeRevenue(currency), feeReturned)
                .credit(AccountRef.refundClearing(event.acquirerId(), currency), refunded)
                .build());

        if (recorded) {
            log.info(
                    "payment={} refund={} amount={} feeReturned={}",
                    event.paymentId(),
                    event.refundId(),
                    refunded.toDisplayString(),
                    feeReturned.toDisplayString());
        }
    }

    /** The authorization was released before capture. The hold ends; nothing is posted. */
    @Transactional
    public void onVoided(EventEnvelope<VoidSucceeded> envelope) {
        String paymentId = envelope.payload().paymentId();
        boolean released = holds.release(paymentId, HoldRepository.HoldStatus.RELEASED);
        log.info("payment={} hold={}", paymentId, released ? "released" : "was not active");
    }

    /** The authorization lapsed. Same as a void, from the books' point of view. */
    @Transactional
    public void onExpired(EventEnvelope<AuthorizationExpired> envelope) {
        String paymentId = envelope.payload().paymentId();
        boolean released = holds.release(paymentId, HoldRepository.HoldStatus.EXPIRED);
        log.info("payment={} hold={}", paymentId, released ? "expired" : "was not active");
    }
}
