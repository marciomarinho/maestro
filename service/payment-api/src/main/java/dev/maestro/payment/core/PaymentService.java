package dev.maestro.payment.core;

import dev.maestro.domain.id.Ids;
import dev.maestro.domain.money.Money;
import dev.maestro.domain.payment.CaptureMethod;
import dev.maestro.domain.payment.PaymentStatus;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.outbox.OutboxWriter;
import dev.maestro.payment.idempotency.IdempotencyRepository;
import dev.maestro.payment.security.TenantContext;
import dev.maestro.payment.web.ApiException;
import dev.maestro.payment.web.CreatePaymentRequest;
import dev.maestro.payment.web.PaymentResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The merchant-facing payment operations.
 *
 * <p>Each public method is one database transaction that does three things atomically:
 * claims the idempotency key, applies the state change, and appends the outbox event.
 * Because all three commit together there is no ordering in which a crash leaves the
 * system inconsistent — no payment without its key, no state change without its
 * instruction (ADR-0004, ADR-0013).
 */
@Service
public class PaymentService {

    private static final String ENDPOINT_CREATE = "POST /v1/payments";
    private static final String ENDPOINT_CONFIRM = "POST /v1/payments/{id}/confirm";
    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository payments;
    private final IdempotencyRepository idempotency;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public PaymentService(
            PaymentRepository payments,
            IdempotencyRepository idempotency,
            OutboxWriter outbox,
            ObjectMapper json) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.json = json;
    }

    /** The result of an operation, and whether it was served from a previous one. */
    public record Result(HttpStatus status, PaymentResponse body, boolean replayed) {
    }

    @Transactional
    public Result create(String idempotencyKey, CreatePaymentRequest request) {
        String merchantId = TenantContext.requireMerchantId();
        Money.parseCurrency(request.currency()); // rejects a bad code before anything is written

        Optional<Result> replay =
                claimOrReplay(merchantId, ENDPOINT_CREATE, idempotencyKey, fingerprint(request));
        if (replay.isPresent()) {
            return replay.get();
        }

        CardToken card = CardToken.parse(request.cardToken());
        boolean confirming = request.confirm();
        Payment payment = new Payment(
                Ids.payment(),
                merchantId,
                request.amountMinor(),
                request.currency(),
                0L,
                0L,
                card.token(),
                card.network().name(),
                card.last4(),
                null,
                confirming ? PaymentStatus.AUTHORIZING : PaymentStatus.CREATED,
                CaptureMethod.valueOf(request.captureMethod()),
                request.reference(),
                json.writeValueAsString(request.metadata()),
                null, null, null, null, null, null, null, null);

        payments.insert(payment);
        if (confirming) {
            appendAuthorizationRequest(payment);
        }

        HttpStatus status = confirming ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        PaymentResponse body = toResponse(payment);
        recordCompletion(merchantId, ENDPOINT_CREATE, idempotencyKey, status, body, payment.id());
        return new Result(status, body, false);
    }

    @Transactional
    public Result confirm(String idempotencyKey, String paymentId) {
        String merchantId = TenantContext.requireMerchantId();

        Optional<Result> replay =
                claimOrReplay(merchantId, ENDPOINT_CONFIRM, idempotencyKey, fingerprint(paymentId));
        if (replay.isPresent()) {
            return replay.get();
        }

        Payment payment = payments.find(merchantId, paymentId)
                .orElseThrow(() -> ApiException.notFound("payment", paymentId));

        // The guard is in the UPDATE, not in an if-statement over the row just read:
        // two concurrent confirmations both see CREATED, but only one changes a row.
        if (payments.transitionToAuthorizing(merchantId, paymentId) == 0) {
            throw ApiException.conflict(
                    "invalid_payment_state",
                    "A payment can only be confirmed from CREATED; this one is %s"
                            .formatted(payment.status()));
        }

        Payment authorizing = payments.find(merchantId, paymentId).orElseThrow();
        appendAuthorizationRequest(authorizing);

        PaymentResponse body = toResponse(authorizing);
        recordCompletion(
                merchantId, ENDPOINT_CONFIRM, idempotencyKey, HttpStatus.ACCEPTED, body, paymentId);
        return new Result(HttpStatus.ACCEPTED, body, false);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(String paymentId) {
        return payments.find(TenantContext.requireMerchantId(), paymentId)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("payment", paymentId));
    }

    /**
     * Claims the key, or returns the outcome of the request that already holds it.
     *
     * @return empty if the caller now owns the key and should perform the effect
     */
    private Optional<Result> claimOrReplay(
            String merchantId, String endpoint, String key, String requestHash) {

        if (idempotency.claim(merchantId, endpoint, key, requestHash)) {
            return Optional.empty();
        }

        var existing = idempotency.find(merchantId, endpoint, key).orElseThrow();

        if (!existing.requestHash().equals(requestHash)) {
            // Returning the first response here would silently hide a genuine merchant
            // defect behind a success.
            throw ApiException.conflict(
                    "idempotency_key_reuse",
                    "This idempotency key was already used with a different request body.");
        }
        if (!existing.isCompleted()) {
            throw ApiException.conflict(
                    "idempotency_request_in_progress",
                    "The original request with this idempotency key is still in progress.");
        }
        return Optional.of(new Result(
                HttpStatus.valueOf(existing.responseStatus()),
                json.readValue(existing.responseBody(), PaymentResponse.class),
                true));
    }

    private void recordCompletion(
            String merchantId,
            String endpoint,
            String key,
            HttpStatus status,
            PaymentResponse body,
            String resourceId) {
        idempotency.complete(
                merchantId, endpoint, key, status.value(), json.writeValueAsString(body), resourceId);
    }

    private void appendAuthorizationRequest(Payment payment) {
        outbox.append(
                EventEnvelope.of(
                        EventTypes.AUTHORIZATION_REQUESTED,
                        payment.merchantId(),
                        payment.id(),
                        new AuthorizationRequested(
                                payment.id(),
                                payment.merchantId(),
                                payment.amountMinor(),
                                payment.currency(),
                                payment.cardToken(),
                                payment.cardNetwork(),
                                payment.captureMethod().name())),
                AGGREGATE_TYPE,
                Topics.PAYMENT_COMMANDS);
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.id(),
                payment.status().name(),
                payment.amountMinor(),
                payment.currency(),
                payment.capturedAmountMinor(),
                payment.refundedAmountMinor(),
                new PaymentResponse.Card(
                        payment.cardNetwork(), payment.cardLast4(), payment.cardCountry()),
                payment.reference(),
                readMetadata(payment.metadataJson()),
                payment.acquirerId(),
                payment.acquirerReference(),
                payment.declineCode(),
                payment.failureReason(),
                payment.createdAt(),
                payment.updatedAt());
    }

    private Map<String, String> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        return json.readValue(metadataJson, new TypeReference<Map<String, String>>() {});
    }

    /**
     * A stable fingerprint of the request, so a replay under the same key with a
     * different body can be detected rather than silently succeeding.
     */
    private String fingerprint(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
