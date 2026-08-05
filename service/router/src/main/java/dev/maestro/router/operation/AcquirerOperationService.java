package dev.maestro.router.operation;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.domain.id.Ids;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
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
import dev.maestro.router.acquirer.AcquirerClient;
import dev.maestro.router.acquirer.AcquirerSelector;
import dev.maestro.router.attempt.Attempt;
import dev.maestro.router.attempt.AttemptRepository;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
 *       the outbox row commit together, a completed attempt always has its event — which is
 *       why redelivery of a completed attempt can simply be skipped.</li>
 * </ol>
 *
 * <p>Only authorization makes a routing decision. Capture, refund and void go to the
 * institution holding the authorization; sending them anywhere else would act against a
 * reference that does not exist there.
 *
 * <p>Phase 1 semantics still apply for retries: one attempt, no failover. Cascading
 * failover, circuit breakers and the retry budget arrive in Phase 3, and the attempt
 * numbering and exclusion set already exist so that lands here rather than in the schema.
 */
@Service
public class AcquirerOperationService {

    private static final Logger log = LoggerFactory.getLogger(AcquirerOperationService.class);
    private static final String AGGREGATE_TYPE = "payment";
    private static final int FIRST_ATTEMPT = 1;

    private final AttemptRepository attempts;
    private final AcquirerSelector selector;
    private final AcquirerClient acquirers;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    public AcquirerOperationService(
            AttemptRepository attempts,
            AcquirerSelector selector,
            AcquirerClient acquirers,
            OutboxWriter outbox,
            TransactionTemplate transactions) {
        this.attempts = attempts;
        this.selector = selector;
        this.acquirers = acquirers;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    // --- authorize ----------------------------------------------------------

    public void authorize(EventEnvelope<AuthorizationRequested> envelope) {
        AuthorizationRequested command = envelope.payload();
        String corridor = command.cardNetwork() + ":" + command.currency();

        AcquirerSelector.Selection selection = selector.select(corridor, Set.of());
        Optional<Attempt> claimed = claim(
                command.paymentId(), command.merchantId(), Attempt.OPERATION_AUTHORIZE,
                selection.acquirerId(), corridor, selection.reason());
        if (claimed.isEmpty()) {
            return;
        }
        Attempt attempt = claimed.get();

        Timed result = timed(() -> acquirers.authorize(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.AuthorizeRequest(
                        command.paymentId(),
                        command.merchantId(),
                        command.amountMinor(),
                        command.currency(),
                        command.cardToken(),
                        command.cardNetwork())));

        transactions.executeWithoutResult(status -> {
            switch (result.outcome()) {
                case AcquirerOutcome.Approved approved -> {
                    complete(attempt, "APPROVED", "00", "Approved", result, approved.acquirerReference());
                    publish(command.paymentId(), command.merchantId(),
                            EventTypes.AUTHORIZATION_SUCCEEDED,
                            new AuthorizationSucceeded(
                                    command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                    approved.acquirerReference(), approved.authorizationCode(),
                                    command.amountMinor(), command.currency(), attempt.attemptNo()));
                }
                case AcquirerOutcome.BusinessDecline decline -> {
                    // Final everywhere. No other acquirer is asked (ADR-0012).
                    complete(attempt, "DECLINED_BUSINESS", decline.code().name(),
                            decline.message(), result, null);
                    publish(command.paymentId(), command.merchantId(),
                            EventTypes.AUTHORIZATION_DECLINED,
                            new AuthorizationDeclined(
                                    command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                    decline.code().name(), decline.message(), attempt.attemptNo()));
                }
                case AcquirerOutcome.TechnicalFailure failure -> failAuthorization(
                        attempt, command, result, "DECLINED_TECHNICAL", failure.code(), failure.message());
                case AcquirerOutcome.Timeout ignored -> failAuthorization(
                        attempt, command, result, "TIMEOUT", "TIMEOUT",
                        "No response from acquirer within the deadline");
                case AcquirerOutcome.Throttled ignored -> failAuthorization(
                        attempt, command, result, "THROTTLED", "THROTTLED",
                        "Acquirer refused the request on capacity grounds");
            }
        });
    }

    private void failAuthorization(
            Attempt attempt,
            AuthorizationRequested command,
            Timed result,
            String attemptOutcome,
            String responseCode,
            String message) {
        complete(attempt, attemptOutcome, responseCode, message, result, null);
        publish(command.paymentId(), command.merchantId(), EventTypes.AUTHORIZATION_FAILED,
                new AuthorizationFailed(
                        command.paymentId(), command.merchantId(), message, attempt.attemptNo()));
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

        Timed result = timed(() -> acquirers.capture(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.CaptureRequest(
                        command.paymentId(), command.acquirerReference(),
                        command.amountMinor(), command.currency())));

        transactions.executeWithoutResult(status -> {
            if (result.outcome() instanceof AcquirerOutcome.Approved approved) {
                complete(attempt, "APPROVED", "00", "Captured", result, approved.acquirerReference());
                publish(command.paymentId(), command.merchantId(), EventTypes.CAPTURE_SUCCEEDED,
                        new CaptureSucceeded(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                approved.acquirerReference(), command.amountMinor(),
                                command.currency(), attempt.attemptNo()));
            } else {
                Failure failure = describe(result.outcome());
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(), result, null);
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

        Timed result = timed(() -> acquirers.refund(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.RefundRequest(
                        command.refundId(), command.paymentId(), command.acquirerReference(),
                        command.amountMinor(), command.currency())));

        transactions.executeWithoutResult(status -> {
            if (result.outcome() instanceof AcquirerOutcome.Approved approved) {
                complete(attempt, "APPROVED", "00", "Refunded", result, approved.acquirerReference());
                publish(command.paymentId(), command.merchantId(), EventTypes.REFUND_SUCCEEDED,
                        new RefundSucceeded(
                                command.refundId(), command.paymentId(), command.merchantId(),
                                attempt.acquirerId(), approved.acquirerReference(),
                                command.amountMinor(), command.currency(), attempt.attemptNo()));
            } else {
                Failure failure = describe(result.outcome());
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(), result, null);
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

        Timed result = timed(() -> acquirers.voidAuthorization(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.VoidRequest(command.paymentId(), command.acquirerReference())));

        transactions.executeWithoutResult(status -> {
            if (result.outcome() instanceof AcquirerOutcome.Approved approved) {
                complete(attempt, "APPROVED", "00", "Voided", result, approved.acquirerReference());
                publish(command.paymentId(), command.merchantId(), EventTypes.VOID_SUCCEEDED,
                        new VoidSucceeded(
                                command.paymentId(), command.merchantId(),
                                attempt.acquirerId(), attempt.attemptNo()));
            } else {
                Failure failure = describe(result.outcome());
                complete(attempt, failure.attemptOutcome(), failure.code(), failure.message(), result, null);
                publish(command.paymentId(), command.merchantId(), EventTypes.VOID_FAILED,
                        new VoidFailed(
                                command.paymentId(), command.merchantId(), attempt.acquirerId(),
                                failure.message(), attempt.attemptNo()));
            }
        });
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
     * attempt history for a payment describes one consistent route.
     */
    private Optional<Attempt> claimFollowUp(
            String attemptSubjectId,
            String merchantId,
            String operation,
            String acquirerId,
            String paymentIdForCorridor) {
        String corridor = attempts
                .find(paymentIdForCorridor, Attempt.OPERATION_AUTHORIZE, FIRST_ATTEMPT)
                .map(Attempt::corridor)
                .orElse("UNKNOWN");
        return claim(
                attemptSubjectId, merchantId, operation, acquirerId, corridor,
                AcquirerSelector.Selection.REASON_PINNED);
    }

    /**
     * @return the attempt to execute, or empty if this command has already been answered
     */
    private Optional<Attempt> claim(
            String subjectId,
            String merchantId,
            String operation,
            String acquirerId,
            String corridor,
            String selectionReason) {
        return transactions.execute(status -> {
            Optional<Attempt> existing = attempts.find(subjectId, operation, FIRST_ATTEMPT);
            if (existing.isPresent()) {
                // An attempt left in flight means the platform never learned the outcome.
                // Repeating it is safe, because the acquirer idempotency key is the same.
                if (existing.get().isInFlight()) {
                    return existing;
                }
                log.debug("{} for {} already completed; redelivery ignored", operation, subjectId);
                return Optional.<Attempt>empty();
            }

            Attempt attempt = new Attempt(
                    Ids.attempt(), subjectId, merchantId, FIRST_ATTEMPT, operation,
                    acquirerId, corridor, selectionReason, Attempt.OUTCOME_IN_FLIGHT,
                    null, null, null, null);

            if (attempts.claim(attempt)) {
                return Optional.of(attempt);
            }
            // Lost the race to a concurrent consumer; defer to whatever it recorded.
            return attempts.find(subjectId, operation, FIRST_ATTEMPT).filter(Attempt::isInFlight);
        });
    }

    private void complete(
            Attempt attempt,
            String outcome,
            String responseCode,
            String message,
            Timed result,
            String acquirerReference) {
        attempts.complete(
                attempt.id(), outcome, responseCode, message, result.latencyMs(), acquirerReference);
        log.info(
                "subject={} operation={} acquirer={} outcome={} latencyMs={}",
                attempt.paymentId(), attempt.operation(), attempt.acquirerId(),
                outcome, result.latencyMs());
    }

    private void publish(String paymentId, String merchantId, String eventType, Object payload) {
        // Keyed by payment even for refunds, so a refund cannot overtake its capture.
        outbox.append(
                EventEnvelope.of(eventType, merchantId, paymentId, payload),
                AGGREGATE_TYPE,
                Topics.PAYMENT_EVENTS);
    }

    private static Timed timed(java.util.function.Supplier<AcquirerOutcome> call) {
        long startedAt = System.nanoTime();
        AcquirerOutcome outcome = call.get();
        return new Timed(outcome, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static Failure describe(AcquirerOutcome outcome) {
        Function<AcquirerOutcome, Failure> describe = o -> switch (o) {
            case AcquirerOutcome.BusinessDecline decline ->
                    new Failure("DECLINED_BUSINESS", decline.code().name(), decline.message());
            case AcquirerOutcome.TechnicalFailure failure ->
                    new Failure("DECLINED_TECHNICAL", failure.code(), failure.message());
            case AcquirerOutcome.Timeout ignored ->
                    new Failure("TIMEOUT", "TIMEOUT", "No response from acquirer within the deadline");
            case AcquirerOutcome.Throttled ignored ->
                    new Failure("THROTTLED", "THROTTLED", "Acquirer refused on capacity grounds");
            case AcquirerOutcome.Approved ignored ->
                    throw new IllegalStateException("Approved is not a failure");
        };
        return describe.apply(outcome);
    }

    private record Timed(AcquirerOutcome outcome, long latencyMs) {
    }

    private record Failure(String attemptOutcome, String code, String message) {
    }
}
