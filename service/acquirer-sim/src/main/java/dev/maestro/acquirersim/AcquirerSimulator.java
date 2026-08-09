package dev.maestro.acquirersim;

import dev.maestro.acquirersim.api.AcquirerResponse;
import dev.maestro.acquirersim.api.AuthorizeRequest;
import dev.maestro.acquirersim.api.CaptureRequest;
import dev.maestro.acquirersim.api.RefundRequest;
import dev.maestro.acquirersim.api.VoidRequest;
import dev.maestro.domain.id.Ids;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The behaviour of the simulated acquirers.
 *
 * <p><strong>Idempotency keys are honoured.</strong> A request repeating a key returns the
 * original answer instead of acting again. This is not decoration: it is what makes the
 * platform's retry logic testable. Without it a duplicate capture would be invisible here,
 * and a timeout retry could silently take a customer's money twice with no test noticing.
 *
 * <p><strong>Only decisions are remembered.</strong> An approval and a decline are answers,
 * so they are recorded and replayed. A technical failure, a timeout and a capacity refusal
 * decided nothing, so they are deliberately <em>not</em> recorded — a retry carrying the
 * same key must be free to succeed. Memoising a failure would quietly poison every retry
 * path in the platform: the router would re-present a timed-out authorization to the same
 * acquirer with the same key, as it is required to, and receive the failure forever.
 *
 * <p><strong>Authorization state is tracked.</strong> A capture is checked against the
 * authorization it names, and against what has already been captured or voided, so the
 * simulator refuses the things a real acquirer would refuse rather than accepting anything
 * the platform sends. A simulator that says yes to everything cannot demonstrate that the
 * platform asks the right questions.
 *
 * <p><strong>Faults are injected at runtime</strong> through {@link Behaviour}, changed
 * while the platform is running. See {@code dev.maestro.acquirersim.api.AdminController}.
 */
@Component
public class AcquirerSimulator {

    private static final Logger log = LoggerFactory.getLogger(AcquirerSimulator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, AcquirerSimProperties.Acquirer> acquirers;

    /** Behaviour as configured, so healing an acquirer has something to return to. */
    private final Map<String, Behaviour> configuredBehaviours;

    /** Behaviour right now, replaced wholesale by the admin API. */
    private final ConcurrentHashMap<String, Behaviour> behaviours = new ConcurrentHashMap<>();

    /** Operations currently executing per acquirer, for the capacity cap. */
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /** Answers already given, keyed by acquirer and idempotency key. */
    private final ConcurrentHashMap<String, CompletableFuture<AcquirerResponse>> answered =
            new ConcurrentHashMap<>();

    /** Authorizations this instance has granted, by its own reference. */
    private final ConcurrentHashMap<String, Authorization> authorizations = new ConcurrentHashMap<>();

    public AcquirerSimulator(AcquirerSimProperties properties) {
        this.acquirers = properties.acquirers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        AcquirerSimProperties.Acquirer::id, Function.identity()));
        this.configuredBehaviours = properties.acquirers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        AcquirerSimProperties.Acquirer::id, AcquirerSimProperties.Acquirer::behaviour));
        this.behaviours.putAll(configuredBehaviours);
    }

    public Map<String, AcquirerSimProperties.Acquirer> acquirers() {
        return acquirers;
    }

    // --- fault injection ----------------------------------------------------

    public Behaviour behaviourOf(String acquirerId) {
        requireAcquirer(acquirerId);
        return behaviours.get(acquirerId);
    }

    /** Replaces behaviour wholesale, so no request ever observes a half-applied change. */
    public Behaviour degrade(String acquirerId, Behaviour behaviour) {
        requireAcquirer(acquirerId);
        behaviours.put(acquirerId, behaviour);
        log.warn("acquirer={} behaviour changed declineRate={} technicalFailureRate={} "
                        + "timeoutRate={} latency={} maxInFlight={}",
                acquirerId, behaviour.declineRate(), behaviour.technicalFailureRate(),
                behaviour.timeoutRate(), behaviour.latency(), behaviour.maxInFlight());
        return behaviour;
    }

    /** Restores the configured behaviour. The other half of every brownout demo. */
    public Behaviour heal(String acquirerId) {
        return degrade(acquirerId, configuredBehaviours.get(requireAcquirer(acquirerId).id()));
    }

    // --- operations ---------------------------------------------------------

    public AcquirerResponse authorize(
            String acquirerId, String idempotencyKey, AuthorizeRequest request) {
        return once(acquirerId, idempotencyKey, () -> {
            String reference = Ids.generate(acquirerId);
            authorizations.put(
                    reference, new Authorization(request.amountMinor(), request.currency()));
            log.info("acquirer={} payment={} amount={} {} outcome=APPROVED reference={}",
                    acquirerId, request.paymentId(), request.amountMinor(),
                    request.currency(), reference);
            return AcquirerResponse.approved(reference, authorizationCode());
        });
    }

    public AcquirerResponse capture(
            String acquirerId, String idempotencyKey, CaptureRequest request) {
        return once(acquirerId, idempotencyKey, () -> {
            Authorization authorization = authorizations.get(request.acquirerReference());
            if (authorization == null) {
                return AcquirerResponse.technicalFailure(
                        "UNKNOWN_AUTHORIZATION",
                        "No authorization with reference " + request.acquirerReference());
            }
            synchronized (authorization) {
                if (authorization.voided) {
                    return AcquirerResponse.businessDecline(
                            "RESTRICTED_CARD", "The authorization has been voided");
                }
                if (request.amountMinor() > authorization.remaining()) {
                    return AcquirerResponse.businessDecline(
                            "LIMIT_EXCEEDED",
                            "Capture of %d exceeds the %d remaining on this authorization"
                                    .formatted(request.amountMinor(), authorization.remaining()));
                }
                authorization.captured += request.amountMinor();
            }
            log.info("acquirer={} payment={} captured={} reference={}",
                    acquirerId, request.paymentId(), request.amountMinor(),
                    request.acquirerReference());
            return AcquirerResponse.approved(request.acquirerReference(), null);
        });
    }

    public AcquirerResponse refund(String acquirerId, String idempotencyKey, RefundRequest request) {
        return once(acquirerId, idempotencyKey, () -> {
            Authorization authorization = authorizations.get(request.acquirerReference());
            if (authorization == null) {
                return AcquirerResponse.technicalFailure(
                        "UNKNOWN_AUTHORIZATION",
                        "No authorization with reference " + request.acquirerReference());
            }
            synchronized (authorization) {
                if (request.amountMinor() > authorization.captured - authorization.refunded) {
                    return AcquirerResponse.businessDecline(
                            "LIMIT_EXCEEDED", "Refund exceeds what was captured");
                }
                authorization.refunded += request.amountMinor();
            }
            String reference = Ids.generate(acquirerId + "_rfnd");
            log.info("acquirer={} payment={} refund={} amount={} reference={}",
                    acquirerId, request.paymentId(), request.refundId(),
                    request.amountMinor(), reference);
            return AcquirerResponse.approved(reference, null);
        });
    }

    public AcquirerResponse voidAuthorization(
            String acquirerId, String idempotencyKey, VoidRequest request) {
        return once(acquirerId, idempotencyKey, () -> {
            Authorization authorization = authorizations.get(request.acquirerReference());
            if (authorization == null) {
                return AcquirerResponse.technicalFailure(
                        "UNKNOWN_AUTHORIZATION",
                        "No authorization with reference " + request.acquirerReference());
            }
            synchronized (authorization) {
                if (authorization.captured > 0) {
                    return AcquirerResponse.businessDecline(
                            "RESTRICTED_CARD", "Captured funds cannot be voided; refund instead");
                }
                authorization.voided = true;
            }
            log.info("acquirer={} payment={} voided reference={}",
                    acquirerId, request.paymentId(), request.acquirerReference());
            return AcquirerResponse.approved(request.acquirerReference(), null);
        });
    }

    // --- idempotency and faults ---------------------------------------------

    /**
     * Performs the operation at most once per idempotency key.
     *
     * <p>A placeholder future is published before the work starts, so a concurrent duplicate
     * finds it and waits for the original answer rather than performing a second one. This is
     * the guarantee a real acquirer gives and the one the platform's retry logic depends on.
     *
     * <p>It is a future rather than the response itself because the work can now take thirty
     * seconds — a simulated timeout — and computing inside {@code computeIfAbsent} would hold
     * a lock on the map bin for the duration, stalling unrelated payments that happen to hash
     * beside it. Under a blackout that is most of them.
     */
    private AcquirerResponse once(
            String acquirerId, String idempotencyKey, Supplier<AcquirerResponse> operation) {
        requireAcquirer(acquirerId);
        String key = acquirerId + ":" + idempotencyKey;

        CompletableFuture<AcquirerResponse> answer = new CompletableFuture<>();
        CompletableFuture<AcquirerResponse> inProgress = answered.putIfAbsent(key, answer);
        if (inProgress != null) {
            return inProgress.join();
        }

        try {
            AcquirerResponse response = attempt(acquirerId, operation);
            answer.complete(response);
            if (!response.isDecision()) {
                // Nothing was decided, so nothing is remembered. A concurrent duplicate may
                // already have joined this failure, which is exactly what a real acquirer
                // does with two simultaneous copies of a request its systems could not serve.
                answered.remove(key, answer);
            }
            return response;
        } catch (RuntimeException e) {
            answered.remove(key, answer);
            answer.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Runs one operation under the acquirer's current behaviour.
     *
     * <p>Order matters and mirrors reality: capacity is refused at the door before any work
     * is done; a request that is going to hang never reaches the issuer; latency is paid
     * before any answer; and a systems failure preempts the issuer's decision, because when
     * the acquirer's own plumbing fails the issuer is never asked.
     */
    private AcquirerResponse attempt(String acquirerId, Supplier<AcquirerResponse> operation) {
        Behaviour behaviour = behaviours.get(acquirerId);
        AtomicInteger concurrent = inFlight.computeIfAbsent(acquirerId, id -> new AtomicInteger());

        int current = concurrent.incrementAndGet();
        try {
            if (behaviour.maxInFlight() > 0 && current > behaviour.maxInFlight()) {
                log.info("acquirer={} refused on capacity inFlight={} cap={}",
                        acquirerId, current, behaviour.maxInFlight());
                return AcquirerResponse.throttled(behaviour.latency().toMillis());
            }
            if (draw() < behaviour.timeoutRate()) {
                log.info("acquirer={} injecting timeout", acquirerId);
                sleep(Behaviour.HANG);
                return AcquirerResponse.technicalFailure(
                        "GATEWAY_TIMEOUT", "The issuer did not respond");
            }

            sleep(roundTrip(behaviour));

            if (draw() < behaviour.technicalFailureRate()) {
                log.info("acquirer={} injecting technical failure", acquirerId);
                return AcquirerResponse.technicalFailure(
                        "ISSUER_UNAVAILABLE", "The issuer could not be reached");
            }
            if (draw() < behaviour.declineRate()) {
                log.info("acquirer={} injecting business decline", acquirerId);
                return AcquirerResponse.businessDecline(
                        "INSUFFICIENT_FUNDS", "The account has insufficient funds");
            }
            return operation.get();
        } finally {
            concurrent.decrementAndGet();
        }
    }

    private static Duration roundTrip(Behaviour behaviour) {
        long jitterMillis = behaviour.latencyJitter().toMillis();
        return jitterMillis == 0
                ? behaviour.latency()
                : behaviour.latency().plusMillis(ThreadLocalRandom.current().nextLong(jitterMillis));
    }

    private static double draw() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating acquirer latency", e);
        }
    }

    private AcquirerSimProperties.Acquirer requireAcquirer(String acquirerId) {
        AcquirerSimProperties.Acquirer acquirer = acquirers.get(acquirerId);
        if (acquirer == null) {
            throw new NoSuchElementException("No such acquirer: " + acquirerId);
        }
        return acquirer;
    }

    private static String authorizationCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** What an acquirer remembers about an authorization it granted. */
    private static final class Authorization {

        private final long amountMinor;
        private final String currency;
        private long captured;
        private long refunded;
        private boolean voided;

        private Authorization(long amountMinor, String currency) {
            this.amountMinor = amountMinor;
            this.currency = currency;
        }

        private long remaining() {
            return amountMinor - captured;
        }
    }
}
