package dev.maestro.acquirersim;

import dev.maestro.acquirersim.api.AcquirerResponse;
import dev.maestro.acquirersim.api.AuthorizeRequest;
import dev.maestro.acquirersim.api.CaptureRequest;
import dev.maestro.acquirersim.api.RefundRequest;
import dev.maestro.acquirersim.api.VoidRequest;
import dev.maestro.domain.id.Ids;
import java.security.SecureRandom;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><strong>Authorization state is tracked.</strong> A capture is checked against the
 * authorization it names, and against what has already been captured or voided, so the
 * simulator refuses the things a real acquirer would refuse rather than accepting anything
 * the platform sends. A simulator that says yes to everything cannot demonstrate that the
 * platform asks the right questions.
 *
 * <p>Decline rates, latency distributions, throughput caps and brownout modes arrive in
 * Phase 3 with the routing logic that reacts to them.
 */
@Component
public class AcquirerSimulator {

    private static final Logger log = LoggerFactory.getLogger(AcquirerSimulator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, AcquirerSimProperties.Acquirer> acquirers;

    /** Answers already given, keyed by acquirer and idempotency key. */
    private final ConcurrentHashMap<String, AcquirerResponse> answered = new ConcurrentHashMap<>();

    /** Authorizations this instance has granted, by its own reference. */
    private final ConcurrentHashMap<String, Authorization> authorizations = new ConcurrentHashMap<>();

    public AcquirerSimulator(AcquirerSimProperties properties) {
        this.acquirers = properties.acquirers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        AcquirerSimProperties.Acquirer::id, Function.identity()));
    }

    public Map<String, AcquirerSimProperties.Acquirer> acquirers() {
        return acquirers;
    }

    public AcquirerResponse authorize(
            String acquirerId, String idempotencyKey, AuthorizeRequest request) {
        return once(acquirerId, idempotencyKey, () -> {
            simulateIssuerRoundTrip(acquirerId);
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
            simulateIssuerRoundTrip(acquirerId);
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
            simulateIssuerRoundTrip(acquirerId);
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
            simulateIssuerRoundTrip(acquirerId);
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

    /**
     * Performs the operation once per idempotency key.
     *
     * <p>{@code computeIfAbsent} makes this atomic per key, so two concurrent retries
     * cannot both act — which is exactly the guarantee a real acquirer gives and the one
     * the platform's retry logic depends on.
     */
    private AcquirerResponse once(
            String acquirerId, String idempotencyKey, Supplier<AcquirerResponse> operation) {
        requireAcquirer(acquirerId);
        return answered.computeIfAbsent(acquirerId + ":" + idempotencyKey, key -> operation.get());
    }

    private AcquirerSimProperties.Acquirer requireAcquirer(String acquirerId) {
        AcquirerSimProperties.Acquirer acquirer = acquirers.get(acquirerId);
        if (acquirer == null) {
            throw new NoSuchElementException("No such acquirer: " + acquirerId);
        }
        return acquirer;
    }

    private void simulateIssuerRoundTrip(String acquirerId) {
        try {
            Thread.sleep(acquirers.get(acquirerId).latency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating acquirer latency", e);
        }
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
