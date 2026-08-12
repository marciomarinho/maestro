package dev.maestro.router.operation;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.domain.id.Ids;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AttemptRecorded;
import dev.maestro.events.payload.AuthorizationDeclined;
import dev.maestro.events.payload.AuthorizationFailed;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.events.payload.CaptureFailed;
import dev.maestro.events.payload.CaptureRequested;
import dev.maestro.events.payload.CaptureSucceeded;
import dev.maestro.events.payload.RefundFailed;
import dev.maestro.events.payload.RefundRequested;
import dev.maestro.events.payload.RefundSucceeded;
import dev.maestro.events.payload.VoidFailed;
import dev.maestro.events.payload.VoidRequested;
import dev.maestro.events.payload.VoidSucceeded;
import dev.maestro.outbox.OutboxWriter;
import dev.maestro.router.RouterProperties;
import dev.maestro.router.acquirer.AcquirerClient;
import dev.maestro.router.acquirer.AcquirerSelector;
import dev.maestro.router.attempt.Attempt;
import dev.maestro.router.attempt.AttemptRepository;
import dev.maestro.router.health.CorridorKey;
import dev.maestro.router.health.HealthRegistry;
import dev.maestro.router.observability.AttemptMetrics;
import dev.maestro.router.resilience.CircuitBreakers;
import dev.maestro.router.resilience.RetryBudget;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes acquirer operations: authorize, capture, refund and void.
 *
 * <p>All four share one shape, dictated by a single constraint: <strong>an acquirer call
 * must not happen inside a database transaction.</strong> Holding one open across a network
 * call to a bank ties a connection to someone else's latency and turns an acquirer
 * brownout into connection-pool exhaustion. So each operation is three steps:
 *
 * <ol>
 *   <li><em>Claim</em> the attempt in its own transaction. The unique key on
 *       {@code (payment_id, operation, attempt_no)} is the idempotency guard: a redelivered
 *       command cannot start a second attempt.</li>
 *   <li><em>Call</em> the acquirer outside any transaction, with an idempotency key derived
 *       from the attempt, so a retry returns the original answer rather than acting twice.</li>
 *   <li><em>Complete and publish</em> in one transaction. Because the attempt outcome and
 *       the outbox row commit together, a completed final attempt always has its event.</li>
 * </ol>
 *
 * <p><strong>Only authorization makes a routing decision.</strong> Capture, refund and void
 * go to the institution holding the authorization; sending them anywhere else would act
 * against a reference that does not exist there. That asymmetry is why authorization has a
 * failover loop and the others have a single acquirer and a retry.
 *
 * <h2>The two kinds of retry, and why they must not be confused</h2>
 *
 * <p>They look alike and are opposites.
 *
 * <ul>
 *   <li>A <strong>technical failure</strong> is an answer: the acquirer's systems reported
 *       that nothing happened. Nothing happened, so another acquirer may be asked. That is
 *       a <em>failover</em>: new attempt number, new idempotency key, new institution.</li>
 *   <li>A <strong>timeout</strong> is the absence of an answer. The payment's fate is
 *       unknown, and the acquirer may well have authorized it. Asking a different bank here
 *       is one of the few ways this platform could authorize the same payment twice. So a
 *       timeout is re-presented to the <em>same</em> acquirer with the <em>same</em> key,
 *       and it is that acquirer — the only party that knows — which resolves it.</li>
 * </ul>
 *
 * <p>A business decline is neither. It is the issuer's answer, it is final everywhere, and
 * it is never re-presented to anyone (ADR-0012).
 */
@Service
public class AcquirerOperationService {

    private static final Logger log = LoggerFactory.getLogger(AcquirerOperationService.class);
    private static final String AGGREGATE_TYPE = "payment";
    private static final int FIRST_ATTEMPT = 1;

    private final AttemptRepository attempts;
    private final AcquirerSelector selector;
    private final AcquirerClient acquirers;
    private final HealthRegistry health;
    private final CircuitBreakers breakers;
    private final RetryBudget retryBudget;
    private final RouterProperties.Failover failover;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final AttemptMetrics attemptMetrics;

    public AcquirerOperationService(
            AttemptRepository attempts,
            AcquirerSelector selector,
            AcquirerClient acquirers,
            HealthRegistry health,
            CircuitBreakers breakers,
            RetryBudget retryBudget,
            RouterProperties properties,
            OutboxWriter outbox,
            TransactionTemplate transactions,
            AttemptMetrics attemptMetrics) {
        this.attempts = attempts;
        this.selector = selector;
        this.acquirers = acquirers;
        this.health = health;
        this.breakers = breakers;
        this.retryBudget = retryBudget;
        this.failover = properties.failover();
        this.outbox = outbox;
        this.transactions = transactions;
        this.attemptMetrics = attemptMetrics;
    }

    // --- authorize ----------------------------------------------------------

    /**
     * Offers a payment to acquirers until one answers or the platform runs out of ways to
     * ask.
     *
     * <p>The loop is bounded three separate ways, and each bound is load-bearing. The
     * attempt count stops one payment walking every bank in the country. The candidate
     * check stops it re-offering to acquirers that have already failed it or whose
     * breakers are open. The retry budget stops <em>all</em> payments doing any of this at
     * once, which is the failure that turns one acquirer's outage into an outage of the
     * platform.
     */
    public void authorize(EventEnvelope<AuthorizationRequested> envelope) {
        AuthorizationRequested command = envelope.payload();
        String corridor = command.cardNetwork() + ":" + command.currency();

        if (attempts.isAnswered(command.paymentId(), Attempt.OPERATION_AUTHORIZE)) {
            log.debug("Authorization for {} already answered; redelivery ignored", command.paymentId());
            return;
        }
        retryBudget.recordRequest();

        Set<String> tried = new HashSet<>(
                attempts.acquirersTried(command.paymentId(), Attempt.OPERATION_AUTHORIZE));

        for (int offered = 0; offered < failover.maxAcquirers(); offered++) {
            Optional<Attempt> claimed;
            try {
                claimed = claimAuthorization(command, corridor, tried);
            } catch (AcquirerSelector.NoAcquirerAvailableException e) {
                failWithNowhereToGo(command, e.getMessage());
                return;
            }
            if (claimed.isEmpty()) {
                // Lost the race to a concurrent consumer, which owns publishing the outcome.
                return;
            }
            Attempt attempt = claimed.get();
            tried.add(attempt.acquirerId());

            Timed result = callWithSameAcquirerRetries(attempt, () -> acquirers.authorize(
                    attempt.acquirerId(),
                    attempt.acquirerIdempotencyKey(),
                    new AcquirerClient.AuthorizeRequest(
                            command.paymentId(),
                            command.merchantId(),
                            command.amountMinor(),
                            command.currency(),
                            command.cardToken(),
                            command.cardNetwork())));

            observe(attempt, result);

            if (settleAuthorization(command, corridor, attempt, result, tried, offered)) {
                return;
            }
            backoff(offered);
        }
    }

    /**
     * Writes down how this attempt ended, and says whether the payment is finished with.
     *
     * @return true when the operation is over — approved, declined, or failed with nowhere
     *         left to go. False means keep going: this attempt has been recorded as one the
     *         router walked away from, and no event has been published
     */
    private boolean settleAuthorization(
            AuthorizationRequested command,
            String corridor,
            Attempt attempt,
            Timed result,
            Set<String> tried,
            int offered) {

        return Boolean.TRUE.equals(transactions.execute(status -> switch (result.outcome()) {
            case AcquirerOutcome.Approved approved -> {
                complete(attempt, Attempt.OUTCOME_APPROVED, "00", "Approved", result,
                        approved.acquirerReference(), true);
                publish(command.paymentId(), command.merchantId(),
                        EventTypes.AUTHORIZATION_SUCCEEDED,
                        new AuthorizationSucceeded(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                approved.acquirerReference(), approved.authorizationCode(),
                                command.amountMinor(), command.currency(), attempt.attemptNo()));
                yield true;
            }
            case AcquirerOutcome.BusinessDecline decline -> {
                // The issuer decided. No other acquirer is asked, ever (ADR-0012).
                complete(attempt, Attempt.OUTCOME_DECLINED_BUSINESS, decline.code().name(),
                        decline.message(), result, null, true);
                publish(command.paymentId(), command.merchantId(),
                        EventTypes.AUTHORIZATION_DECLINED,
                        new AuthorizationDeclined(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                decline.code().name(), decline.message(), attempt.attemptNo()));
                yield true;
            }
            default -> {
                Failure failure = describe(result.outcome());
                boolean cascading = mayCascade(command, corridor, result.outcome(), tried, offered);
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(),
                        result, null, !cascading);
                if (!cascading) {
                    publish(command.paymentId(), command.merchantId(),
                            EventTypes.AUTHORIZATION_FAILED,
                            new AuthorizationFailed(
                                    command.paymentId(), command.merchantId(),
                                    failure.message(), attempt.attemptNo()));
                }
                yield !cascading;
            }
        }));
    }

    /**
     * Whether this failure earns another acquirer.
     *
     * <p>Order matters: the retry budget is consulted last, because asking it spends from
     * it. Checking it before the cheaper conditions would charge the platform for
     * failovers it was never going to make.
     */
    private boolean mayCascade(
            AuthorizationRequested command,
            String corridor,
            AcquirerOutcome outcome,
            Set<String> tried,
            int offered) {

        if (!outcome.mayFailOverToAnotherAcquirer()) {
            // A timeout lands here: unresolved, but not something to hand to another bank.
            return false;
        }
        if (offered + 1 >= failover.maxAcquirers()) {
            return false;
        }
        if (!selector.hasCandidate(
                new AcquirerSelector.Request(corridor, command.amountMinor(), tried))) {
            log.info("payment={} no acquirer left to try after {}", command.paymentId(), tried);
            return false;
        }
        if (!retryBudget.tryConsume()) {
            log.warn("payment={} failing over refused by the retry budget", command.paymentId());
            return false;
        }
        return true;
    }

    private Optional<Attempt> claimAuthorization(
            AuthorizationRequested command, String corridor, Set<String> tried) {

        return transactions.execute(status -> {
            Optional<Attempt> resumable =
                    attempts.findInFlight(command.paymentId(), Attempt.OPERATION_AUTHORIZE);
            if (resumable.isPresent()) {
                // A call the platform started and never learned the outcome of. Repeating
                // it is safe and necessary: the idempotency key is the same, so the
                // acquirer replays its original answer rather than acting twice.
                return resumable;
            }

            AcquirerSelector.Selection selection = selector.select(
                    new AcquirerSelector.Request(corridor, command.amountMinor(), tried));

            int attemptNo = attempts.highestAttemptNo(
                    command.paymentId(), Attempt.OPERATION_AUTHORIZE) + 1;
            Attempt attempt = new Attempt(
                    Ids.attempt(), command.paymentId(), command.merchantId(), attemptNo,
                    Attempt.OPERATION_AUTHORIZE, selection.acquirerId(), corridor,
                    selection.reason(), selection.healthScore(), Attempt.OUTCOME_IN_FLIGHT,
                    null, null, null, null, false);

            if (attempts.claim(attempt)) {
                return Optional.of(attempt);
            }
            // Lost the race to a concurrent consumer; defer to whatever it recorded.
            return attempts
                    .find(command.paymentId(), Attempt.OPERATION_AUTHORIZE, attemptNo)
                    .filter(Attempt::isInFlight);
        });
    }

    /**
     * Publishes an authorization failure when the payment could not even be attempted.
     *
     * <p>Reached when every acquirer on the corridor is excluded or circuit-broken. Saying
     * nothing would be worse than saying no: the payment would sit in {@code AUTHORIZING}
     * until it expired, and the merchant would learn about a total outage from a timeout
     * rather than from an answer.
     */
    private void failWithNowhereToGo(AuthorizationRequested command, String detail) {
        log.warn("payment={} has nowhere to go: {}", command.paymentId(), detail);
        transactions.executeWithoutResult(status -> {
            int attemptNo = attempts.highestAttemptNo(
                    command.paymentId(), Attempt.OPERATION_AUTHORIZE);
            attempts.markLatestFinal(command.paymentId(), Attempt.OPERATION_AUTHORIZE);
            publish(command.paymentId(), command.merchantId(), EventTypes.AUTHORIZATION_FAILED,
                    new AuthorizationFailed(
                            command.paymentId(), command.merchantId(), detail, attemptNo));
        });
    }

    // --- capture ------------------------------------------------------------

    public void capture(EventEnvelope<CaptureRequested> envelope) {
        CaptureRequested command = envelope.payload();
        Optional<Attempt> claimed = claimFollowUp(
                command.paymentId(), command.merchantId(), Attempt.OPERATION_CAPTURE,
                command.acquirerId());
        if (claimed.isEmpty()) {
            return;
        }
        Attempt attempt = claimed.get();

        Timed result = callWithSameAcquirerRetries(attempt, () -> acquirers.capture(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.CaptureRequest(
                        command.paymentId(), command.acquirerReference(),
                        command.amountMinor(), command.currency())));

        observeFollowUp(attempt, result);

        transactions.executeWithoutResult(status -> {
            if (result.outcome() instanceof AcquirerOutcome.Approved approved) {
                complete(attempt, Attempt.OUTCOME_APPROVED, "00", "Captured", result,
                        approved.acquirerReference(), true);
                publish(command.paymentId(), command.merchantId(), EventTypes.CAPTURE_SUCCEEDED,
                        new CaptureSucceeded(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                approved.acquirerReference(), command.amountMinor(),
                                command.currency(), attempt.attemptNo()));
            } else {
                Failure failure = describe(result.outcome());
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(),
                        result, null, true);
                publish(command.paymentId(), command.merchantId(), EventTypes.CAPTURE_FAILED,
                        new CaptureFailed(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                failure.message(), attempt.attemptNo()));
            }
        });
    }

    // --- refund -------------------------------------------------------------

    public void refund(EventEnvelope<RefundRequested> envelope) {
        RefundRequested command = envelope.payload();
        // Keyed by refund rather than payment, because a payment may be refunded several
        // times and each is an independent operation with its own acquirer call.
        Optional<Attempt> claimed = claimFollowUp(
                command.refundId(), command.merchantId(), Attempt.OPERATION_REFUND,
                command.acquirerId(), command.paymentId());
        if (claimed.isEmpty()) {
            return;
        }
        Attempt attempt = claimed.get();

        Timed result = callWithSameAcquirerRetries(attempt, () -> acquirers.refund(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.RefundRequest(
                        command.refundId(), command.paymentId(), command.acquirerReference(),
                        command.amountMinor(), command.currency())));

        observeFollowUp(attempt, result);

        transactions.executeWithoutResult(status -> {
            if (result.outcome() instanceof AcquirerOutcome.Approved approved) {
                complete(attempt, Attempt.OUTCOME_APPROVED, "00", "Refunded", result,
                        approved.acquirerReference(), true);
                publish(command.paymentId(), command.merchantId(), EventTypes.REFUND_SUCCEEDED,
                        new RefundSucceeded(
                                command.refundId(), command.paymentId(), command.merchantId(),
                                attempt.acquirerId(), approved.acquirerReference(),
                                command.amountMinor(), command.currency(), attempt.attemptNo()));
            } else {
                Failure failure = describe(result.outcome());
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(),
                        result, null, true);
                publish(command.paymentId(), command.merchantId(), EventTypes.REFUND_FAILED,
                        new RefundFailed(
                                command.refundId(), command.paymentId(), command.merchantId(),
                                command.amountMinor(), command.currency(),
                                failure.message(), attempt.attemptNo()));
            }
        });
    }

    // --- void ---------------------------------------------------------------

    public void voidAuthorization(EventEnvelope<VoidRequested> envelope) {
        VoidRequested command = envelope.payload();
        Optional<Attempt> claimed = claimFollowUp(
                command.paymentId(), command.merchantId(), Attempt.OPERATION_VOID,
                command.acquirerId());
        if (claimed.isEmpty()) {
            return;
        }
        Attempt attempt = claimed.get();

        Timed result = callWithSameAcquirerRetries(attempt, () -> acquirers.voidAuthorization(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.VoidRequest(command.paymentId(), command.acquirerReference())));

        observeFollowUp(attempt, result);

        transactions.executeWithoutResult(status -> {
            if (result.outcome() instanceof AcquirerOutcome.Approved approved) {
                complete(attempt, Attempt.OUTCOME_APPROVED, "00", "Voided", result,
                        approved.acquirerReference(), true);
                publish(command.paymentId(), command.merchantId(), EventTypes.VOID_SUCCEEDED,
                        new VoidSucceeded(
                                command.paymentId(), command.merchantId(),
                                attempt.acquirerId(), attempt.attemptNo()));
            } else {
                Failure failure = describe(result.outcome());
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(),
                        result, null, true);
                publish(command.paymentId(), command.merchantId(), EventTypes.VOID_FAILED,
                        new VoidFailed(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                failure.message(), attempt.attemptNo()));
            }
        });
    }

    // --- calling an acquirer ------------------------------------------------

    /**
     * Makes the call, re-presenting it to the same acquirer while its fate is unknown.
     *
     * <p>The condition isolates exactly one outcome: retryable here but not elsewhere,
     * which is a timeout. A technical failure is also retryable, but it is better failed
     * over — asking a different institution is more likely to succeed than asking the same
     * one that just told us its systems are down.
     */
    private Timed callWithSameAcquirerRetries(Attempt attempt, Supplier<AcquirerOutcome> call) {
        Timed result = timed(call);
        for (int retry = 0; retry < failover.sameAcquirerRetries(); retry++) {
            AcquirerOutcome outcome = result.outcome();
            if (!outcome.retryableOnSameAcquirer() || outcome.mayFailOverToAnotherAcquirer()) {
                break;
            }
            if (!retryBudget.tryConsume()) {
                break;
            }
            log.info("subject={} attempt={} unresolved; re-presenting to {} with the same key",
                    attempt.paymentId(), attempt.attemptNo(), attempt.acquirerId());
            backoff(retry);
            result = timed(call);
        }
        return result;
    }

    /**
     * Waits before trying again: exponential, with full jitter.
     *
     * <p>Full jitter rather than a fixed or merely-capped delay because the failure being
     * recovered from is usually shared. Every payment that hit the same brownout would
     * otherwise wake at the same instant and arrive at the next acquirer as a single
     * synchronised wave — a retry storm assembled by the backoff that was supposed to
     * prevent one.
     *
     * <p>This happens on the consumer thread, which stalls the partition behind it. That is
     * the reason the ceiling is milliseconds rather than seconds: the queue is a better
     * place to wait than a sleeping consumer, and anything long enough to matter belongs
     * back on the topic rather than here.
     */
    private void backoff(int attemptIndex) {
        long exponential = failover.backoff().toMillis() << Math.min(attemptIndex, 10);
        long ceiling = Math.min(exponential, failover.maxBackoff().toMillis());
        long delay = ceiling <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling + 1);
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }

    /** Folds a routed attempt's outcome into health and the corridor's breaker. */
    private void observe(Attempt attempt, Timed result) {
        CorridorKey key = new CorridorKey(attempt.acquirerId(), attempt.corridor());
        health.record(key, result.outcome(), result.latencyMs());
        recordReachability(key, result.outcome());
    }

    /** The same, for an operation that was pinned rather than routed. */
    private void observeFollowUp(Attempt attempt, Timed result) {
        CorridorKey key = new CorridorKey(attempt.acquirerId(), attempt.corridor());
        health.recordFollowUp(key, result.outcome(), result.latencyMs());
        recordReachability(key, result.outcome());
    }

    private void recordReachability(CorridorKey key, AcquirerOutcome outcome) {
        switch (outcome) {
            case AcquirerOutcome.Approved ignored -> breakers.recordAnswer(key);
            case AcquirerOutcome.BusinessDecline ignored -> breakers.recordAnswer(key);
            default -> breakers.recordFailure(key);
        }
    }

    // --- shared -------------------------------------------------------------

    private Optional<Attempt> claimFollowUp(
            String attemptSubjectId, String merchantId, String operation, String acquirerId) {
        return claimFollowUp(attemptSubjectId, merchantId, operation, acquirerId, attemptSubjectId);
    }

    /**
     * Claims an attempt for an operation that follows an authorization.
     *
     * <p>The corridor is carried over from the authorization rather than recomputed, so the
     * attempt history for a payment describes one consistent route — and so that health
     * recorded from a capture lands on the corridor the payment actually took.
     */
    private Optional<Attempt> claimFollowUp(
            String attemptSubjectId,
            String merchantId,
            String operation,
            String acquirerId,
            String paymentIdForCorridor) {

        String corridor = attempts.historyOf(paymentIdForCorridor).stream()
                .filter(a -> Attempt.OPERATION_AUTHORIZE.equals(a.operation()))
                .filter(a -> Attempt.OUTCOME_APPROVED.equals(a.outcome()))
                .map(Attempt::corridor)
                .findFirst()
                .orElse("UNKNOWN");

        return transactions.execute(status -> {
            if (attempts.isAnswered(attemptSubjectId, operation)) {
                log.debug("{} for {} already completed; redelivery ignored", operation, attemptSubjectId);
                return Optional.<Attempt>empty();
            }
            Optional<Attempt> resumable = attempts.findInFlight(attemptSubjectId, operation);
            if (resumable.isPresent()) {
                return resumable;
            }

            Attempt attempt = new Attempt(
                    Ids.attempt(), attemptSubjectId, merchantId, FIRST_ATTEMPT, operation,
                    acquirerId, corridor, AcquirerSelector.Selection.REASON_PINNED, null,
                    Attempt.OUTCOME_IN_FLIGHT, null, null, null, null, false);

            if (attempts.claim(attempt)) {
                return Optional.of(attempt);
            }
            return attempts.find(attemptSubjectId, operation, FIRST_ATTEMPT).filter(Attempt::isInFlight);
        });
    }

    private void complete(
            Attempt attempt,
            String outcome,
            String responseCode,
            String message,
            Timed result,
            String acquirerReference,
            boolean finalAttempt) {
        attempts.complete(
                attempt.id(), outcome, responseCode, message, result.latencyMs(),
                acquirerReference, finalAttempt);

        // Published for every attempt, including ones the router walked away from. The
        // failed half of a failover is the half worth explaining, and it commits in the
        // same transaction as the attempt row so the audit trail cannot lose an entry the
        // router acted on (ADR-0017).
        publish(attempt.paymentId(), attempt.merchantId(), EventTypes.ATTEMPT_RECORDED,
                new AttemptRecorded(
                        attempt.paymentId(), attempt.merchantId(), attempt.operation(),
                        attempt.attemptNo(), attempt.acquirerId(), attempt.corridor(),
                        attempt.selectionReason(), attempt.healthScore(), outcome,
                        responseCode, message, result.latencyMs(), finalAttempt));

        attemptMetrics.record(
                attempt.acquirerId(), attempt.corridor(), attempt.operation(),
                outcome, result.latencyMs());

        log.info(
                "subject={} operation={} attempt={} acquirer={} outcome={} latencyMs={} final={}",
                attempt.paymentId(), attempt.operation(), attempt.attemptNo(),
                attempt.acquirerId(), outcome, result.latencyMs(), finalAttempt);
    }

    private void publish(String paymentId, String merchantId, String eventType, Object payload) {
        // Keyed by payment even for refunds, so a refund cannot overtake its capture.
        outbox.append(
                EventEnvelope.of(eventType, merchantId, paymentId, payload),
                AGGREGATE_TYPE,
                Topics.PAYMENT_EVENTS);
    }

    private static Timed timed(Supplier<AcquirerOutcome> call) {
        long startedAt = System.nanoTime();
        AcquirerOutcome outcome = call.get();
        return new Timed(outcome, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static Failure describe(AcquirerOutcome outcome) {
        return switch (outcome) {
            case AcquirerOutcome.BusinessDecline decline ->
                    new Failure(Attempt.OUTCOME_DECLINED_BUSINESS, decline.code().name(),
                            decline.message());
            case AcquirerOutcome.TechnicalFailure failure ->
                    new Failure(Attempt.OUTCOME_DECLINED_TECHNICAL, failure.code(), failure.message());
            case AcquirerOutcome.Timeout ignored ->
                    new Failure(Attempt.OUTCOME_TIMEOUT, "TIMEOUT",
                            "No response from acquirer within the deadline");
            case AcquirerOutcome.Throttled ignored ->
                    new Failure(Attempt.OUTCOME_THROTTLED, "THROTTLED",
                            "Acquirer refused on capacity grounds");
            case AcquirerOutcome.Approved ignored ->
                    throw new IllegalStateException("Approved is not a failure");
        };
    }

    private record Timed(AcquirerOutcome outcome, long latencyMs) {
    }

    private record Failure(String attemptOutcome, String code, String message) {
    }
}
