package dev.maestro.router.authorization;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.domain.id.Ids;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationDeclined;
import dev.maestro.events.payload.AuthorizationFailed;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.events.payload.AuthorizationSucceeded;
import dev.maestro.outbox.OutboxWriter;
import dev.maestro.router.acquirer.AcquirerClient;
import dev.maestro.router.acquirer.AcquirerSelector;
import dev.maestro.router.attempt.Attempt;
import dev.maestro.router.attempt.AttemptRepository;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Obtains an authorization for a payment.
 *
 * <p>The shape of this method is the interesting part, and it is dictated by one
 * constraint: <strong>an acquirer call must not happen inside a database
 * transaction.</strong> Holding a transaction open across a network call to a bank ties
 * a database connection to someone else's latency, and turns an acquirer brownout into
 * connection-pool exhaustion. So the work is three steps:
 *
 * <ol>
 *   <li><em>Claim</em> the attempt in its own transaction. The unique key on
 *       {@code (payment_id, operation, attempt_no)} makes this the idempotency guard:
 *       a redelivered command cannot start a second attempt.</li>
 *   <li><em>Call</em> the acquirer outside any transaction, with an idempotency key
 *       derived from the attempt, so a retry of this attempt returns the original answer
 *       rather than authorizing again.</li>
 *   <li><em>Complete and publish</em> in one transaction. Because the attempt outcome
 *       and the outbox row commit together, a completed attempt always has its event —
 *       which is why redelivery of a completed attempt can simply be skipped.</li>
 * </ol>
 *
 * <p>Phase 1 makes a single attempt. Cascading failover, circuit breakers and the retry
 * budget arrive in Phase 3; the {@code attemptNo} and the exclusion set already exist so
 * that adding them changes this method rather than the schema around it.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);
    private static final String AGGREGATE_TYPE = "payment";
    private static final int FIRST_ATTEMPT = 1;

    private final AttemptRepository attempts;
    private final AcquirerSelector selector;
    private final AcquirerClient acquirers;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    public AuthorizationService(
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

    public void authorize(EventEnvelope<AuthorizationRequested> envelope) {
        AuthorizationRequested command = envelope.payload();
        String corridor = command.cardNetwork() + ":" + command.currency();

        Optional<Attempt> claimed = claimAttempt(command, corridor);
        if (claimed.isEmpty()) {
            log.debug(
                    "payment={} authorization already completed; redelivery ignored",
                    command.paymentId());
            return;
        }
        Attempt attempt = claimed.get();

        long startedAt = System.nanoTime();
        AcquirerOutcome outcome = acquirers.authorize(
                attempt.acquirerId(),
                attempt.acquirerIdempotencyKey(),
                new AcquirerClient.AuthorizeRequest(
                        command.paymentId(),
                        command.merchantId(),
                        command.amountMinor(),
                        command.currency(),
                        command.cardToken(),
                        command.cardNetwork()));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;

        completeAndPublish(attempt, outcome, latencyMs, command);
    }

    /**
     * @return the attempt to execute, or empty if this command has already been answered
     */
    private Optional<Attempt> claimAttempt(AuthorizationRequested command, String corridor) {
        return transactions.execute(status -> {
            Optional<Attempt> existing =
                    attempts.find(command.paymentId(), Attempt.OPERATION_AUTHORIZE, FIRST_ATTEMPT);
            if (existing.isPresent()) {
                // An attempt left in flight means the platform never learned the outcome.
                // Repeating it is safe, because the acquirer idempotency key is the same.
                return existing.filter(Attempt::isInFlight);
            }

            AcquirerSelector.Selection selection = selector.select(corridor, Set.of());
            Attempt attempt = new Attempt(
                    Ids.attempt(),
                    command.paymentId(),
                    command.merchantId(),
                    FIRST_ATTEMPT,
                    Attempt.OPERATION_AUTHORIZE,
                    selection.acquirerId(),
                    corridor,
                    selection.reason(),
                    Attempt.OUTCOME_IN_FLIGHT,
                    null, null, null, null);

            if (attempts.claim(attempt)) {
                return Optional.of(attempt);
            }
            // Lost the race to a concurrent consumer; defer to whatever it recorded.
            return attempts
                    .find(command.paymentId(), Attempt.OPERATION_AUTHORIZE, FIRST_ATTEMPT)
                    .filter(Attempt::isInFlight);
        });
    }

    private void completeAndPublish(
            Attempt attempt,
            AcquirerOutcome outcome,
            long latencyMs,
            AuthorizationRequested command) {

        transactions.executeWithoutResult(status -> {
            // The switch is exhaustive over a sealed type, so a new outcome cannot be
            // added without every branch here being revisited.
            switch (outcome) {
                case AcquirerOutcome.Approved approved -> {
                    attempts.complete(
                            attempt.id(), "APPROVED", "00", "Approved", latencyMs,
                            approved.acquirerReference());
                    publish(
                            EventTypes.AUTHORIZATION_SUCCEEDED,
                            command,
                            new AuthorizationSucceeded(
                                    command.paymentId(),
                                    command.merchantId(),
                                    attempt.acquirerId(),
                                    approved.acquirerReference(),
                                    approved.authorizationCode(),
                                    command.amountMinor(),
                                    command.currency(),
                                    attempt.attemptNo()));
                    log.info(
                            "payment={} acquirer={} outcome=APPROVED latencyMs={}",
                            command.paymentId(), attempt.acquirerId(), latencyMs);
                }
                case AcquirerOutcome.BusinessDecline decline -> {
                    attempts.complete(
                            attempt.id(), "DECLINED_BUSINESS", decline.code().name(),
                            decline.message(), latencyMs, null);
                    publish(
                            EventTypes.AUTHORIZATION_DECLINED,
                            command,
                            new AuthorizationDeclined(
                                    command.paymentId(),
                                    command.merchantId(),
                                    attempt.acquirerId(),
                                    decline.code().name(),
                                    decline.message(),
                                    attempt.attemptNo()));
                    // Final everywhere. No other acquirer is asked (ADR-0012).
                    log.info(
                            "payment={} acquirer={} outcome=DECLINED code={}",
                            command.paymentId(), attempt.acquirerId(), decline.code());
                }
                case AcquirerOutcome.TechnicalFailure failure -> failAuthorization(
                        attempt, command, latencyMs, "DECLINED_TECHNICAL",
                        failure.code(), failure.message());
                case AcquirerOutcome.Timeout timeout -> failAuthorization(
                        attempt, command, latencyMs, "TIMEOUT",
                        "TIMEOUT", "No response from acquirer within the deadline");
                case AcquirerOutcome.Throttled throttled -> failAuthorization(
                        attempt, command, latencyMs, "THROTTLED",
                        "THROTTLED", "Acquirer refused the request on capacity grounds");
            }
        });
    }

    private void failAuthorization(
            Attempt attempt,
            AuthorizationRequested command,
            long latencyMs,
            String attemptOutcome,
            String responseCode,
            String message) {
        attempts.complete(attempt.id(), attemptOutcome, responseCode, message, latencyMs, null);
        publish(
                EventTypes.AUTHORIZATION_FAILED,
                command,
                new AuthorizationFailed(
                        command.paymentId(), command.merchantId(), message, attempt.attemptNo()));
        log.warn(
                "payment={} acquirer={} outcome={} reason={}",
                command.paymentId(), attempt.acquirerId(), attemptOutcome, message);
    }

    private void publish(String eventType, AuthorizationRequested command, Object payload) {
        outbox.append(
                EventEnvelope.of(eventType, command.merchantId(), command.paymentId(), payload),
                AGGREGATE_TYPE,
                Topics.PAYMENT_EVENTS);
    }
}
