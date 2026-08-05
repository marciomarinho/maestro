package dev.maestro.router.acquirer;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.domain.acquirer.DeclineCode;
import dev.maestro.router.RouterProperties;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Talks to acquiring banks over HTTP.
 *
 * <p>Its real job is translation: turning whatever an acquirer says into the platform's
 * {@link AcquirerOutcome}, which is what every routing decision downstream is made on.
 * Getting that mapping right is the difference between a router that works and one that
 * merely looks like it does — in particular, an issuer-side systems failure arrives
 * dressed as a decline but must be classified as a technical failure, or the platform
 * will treat a transient outage as a customer's card being refused (ADR-0012).
 *
 * <p>Every call carries an idempotency key derived from the attempt, so retrying an
 * operation cannot perform it twice at the acquirer.
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    private final Map<String, RestClient> clients;

    /**
     * @param builder the auto-configured builder, not {@code RestClient.builder()}. The
     *                static factory produces a client with default message converters,
     *                which serialise with Jackson's default naming — quietly ignoring the
     *                application's configured conventions and sending a body the other
     *                side rejects. The injected builder carries the application's
     *                converters, and from Phase 4 its tracing instrumentation too.
     */
    public AcquirerClient(RouterProperties properties, RestClient.Builder builder) {
        this.clients = properties.acquirers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        RouterProperties.Acquirer::id,
                        acquirer -> clientFor(builder, acquirer, properties.requestTimeout())));
    }

    private static RestClient clientFor(
            RestClient.Builder builder, RouterProperties.Acquirer acquirer, Duration timeout) {
        // One request factory per acquirer: a shared, mutated factory would apply the
        // last acquirer's timeout to all of them.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(timeout);
        return builder.clone()
                .baseUrl(acquirer.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public AcquirerOutcome authorize(
            String acquirerId, String idempotencyKey, AuthorizeRequest request) {
        return call(acquirerId, idempotencyKey, "authorize", request);
    }

    public AcquirerOutcome capture(
            String acquirerId, String idempotencyKey, CaptureRequest request) {
        return call(acquirerId, idempotencyKey, "capture", request);
    }

    public AcquirerOutcome refund(String acquirerId, String idempotencyKey, RefundRequest request) {
        return call(acquirerId, idempotencyKey, "refund", request);
    }

    public AcquirerOutcome voidAuthorization(
            String acquirerId, String idempotencyKey, VoidRequest request) {
        return call(acquirerId, idempotencyKey, "void", request);
    }

    private AcquirerOutcome call(
            String acquirerId, String idempotencyKey, String operation, Object request) {

        RestClient client = clients.get(acquirerId);
        if (client == null) {
            throw new IllegalStateException("No client configured for acquirer " + acquirerId);
        }

        return interpretFailures(acquirerId, idempotencyKey, () -> interpret(client.post()
                .uri("/acquirer/{acquirerId}/{operation}", acquirerId, operation)
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .body(AcquirerResponse.class)));
    }

    private AcquirerOutcome interpretFailures(
            String acquirerId, String idempotencyKey, Supplier<AcquirerOutcome> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            // No response arrived, so the operation's fate is unknown — the acquirer may
            // have performed it. Deliberately a Timeout rather than a technical failure:
            // this must be resolved with the same acquirer and the same idempotency key,
            // never by asking a different bank.
            log.warn("acquirer={} no response for idempotencyKey={}", acquirerId, idempotencyKey, e);
            return new AcquirerOutcome.Timeout(0L);
        } catch (RuntimeException e) {
            log.warn("acquirer={} call failed for idempotencyKey={}", acquirerId, idempotencyKey, e);
            return new AcquirerOutcome.TechnicalFailure("ACQUIRER_ERROR", e.getMessage());
        }
    }

    private static AcquirerOutcome interpret(AcquirerResponse response) {
        if (response == null) {
            return new AcquirerOutcome.TechnicalFailure("EMPTY_RESPONSE", "Acquirer returned no body");
        }
        return switch (response.outcome()) {
            case "APPROVED" -> new AcquirerOutcome.Approved(
                    response.acquirerReference(), response.authorizationCode());
            case "DECLINED_BUSINESS" -> new AcquirerOutcome.BusinessDecline(
                    declineCodeOf(response.responseCode()), response.responseMessage());
            case "DECLINED_TECHNICAL" -> new AcquirerOutcome.TechnicalFailure(
                    response.responseCode(), response.responseMessage());
            default -> new AcquirerOutcome.TechnicalFailure(
                    "UNMAPPED_OUTCOME", "Unrecognised acquirer outcome: " + response.outcome());
        };
    }

    private static DeclineCode declineCodeOf(String code) {
        try {
            return DeclineCode.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            // An unmapped decline resolves to the most restrictive interpretation.
            // Guessing towards something retryable would be guessing towards
            // re-presenting a transaction an issuer has already refused.
            return DeclineCode.DO_NOT_HONOUR;
        }
    }

    /** What the platform sends an acquirer to obtain an authorization. Tokens only. */
    public record AuthorizeRequest(
            String paymentId,
            String merchantId,
            long amountMinor,
            String currency,
            String cardToken,
            String cardNetwork) {
    }

    /** Takes funds an authorization reserved. May be for less than was authorized. */
    public record CaptureRequest(
            String paymentId,
            String acquirerReference,
            long amountMinor,
            String currency) {
    }

    /** Returns captured funds. A separate movement, with its own reference. */
    public record RefundRequest(
            String refundId,
            String paymentId,
            String acquirerReference,
            long amountMinor,
            String currency) {
    }

    /** Releases an authorization before capture. */
    public record VoidRequest(String paymentId, String acquirerReference) {
    }

    /** What an acquirer answers, for any operation. */
    public record AcquirerResponse(
            String outcome,
            String acquirerReference,
            String authorizationCode,
            String responseCode,
            String responseMessage) {
    }
}
