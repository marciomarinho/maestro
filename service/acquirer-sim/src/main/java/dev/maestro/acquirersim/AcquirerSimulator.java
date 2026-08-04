package dev.maestro.acquirersim;

import dev.maestro.acquirersim.api.AuthorizeRequest;
import dev.maestro.acquirersim.api.AuthorizeResponse;
import dev.maestro.domain.id.Ids;
import java.security.SecureRandom;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The behaviour of the simulated acquirers.
 *
 * <p><strong>Idempotency keys are honoured.</strong> A request repeating a key returns
 * the original answer instead of authorizing again. This is not decoration: it is what
 * makes the platform's retry logic testable. Without it a duplicate authorization
 * would be invisible here, and a timeout retry could silently take a customer's money
 * twice without any test noticing.
 *
 * <p>Phase 1 approves everything after the configured latency. Decline rates, timeouts,
 * throttling and brownouts arrive in Phase 3 with the routing logic that reacts to them.
 */
@Component
public class AcquirerSimulator {

    private static final Logger log = LoggerFactory.getLogger(AcquirerSimulator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, AcquirerSimProperties.Acquirer> acquirers;

    /**
     * Answers already given, keyed by acquirer and idempotency key. In-memory because a
     * simulator has no durability requirement — a restart is a new institution.
     */
    private final ConcurrentHashMap<String, AuthorizeResponse> answered = new ConcurrentHashMap<>();

    public AcquirerSimulator(AcquirerSimProperties properties) {
        this.acquirers = properties.acquirers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        AcquirerSimProperties.Acquirer::id, Function.identity()));
    }

    public Map<String, AcquirerSimProperties.Acquirer> acquirers() {
        return acquirers;
    }

    public AuthorizeResponse authorize(
            String acquirerId, String idempotencyKey, AuthorizeRequest request) {

        AcquirerSimProperties.Acquirer acquirer = acquirers.get(acquirerId);
        if (acquirer == null) {
            throw new NoSuchElementException("No such acquirer: " + acquirerId);
        }

        return answered.computeIfAbsent(acquirerId + ":" + idempotencyKey, key -> {
            simulateIssuerRoundTrip(acquirer);
            AuthorizeResponse response = AuthorizeResponse.approved(
                    Ids.generate(acquirerId), authorizationCode());
            log.info(
                    "acquirer={} payment={} amount={} {} outcome=APPROVED reference={}",
                    acquirerId,
                    request.paymentId(),
                    request.amountMinor(),
                    request.currency(),
                    response.acquirerReference());
            return response;
        });
    }

    private void simulateIssuerRoundTrip(AcquirerSimProperties.Acquirer acquirer) {
        try {
            Thread.sleep(acquirer.latency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating acquirer latency", e);
        }
    }

    private static String authorizationCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
