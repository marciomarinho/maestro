package dev.maestro.payment.core;

import dev.maestro.domain.id.Ids;
import dev.maestro.domain.money.Money;
import dev.maestro.domain.payment.CaptureMethod;
import dev.maestro.domain.payment.PaymentStatus;
import dev.maestro.payment.idempotency.IdempotencyRepository;
import dev.maestro.payment.security.TenantContext;
import dev.maestro.payment.web.ApiException;
import dev.maestro.payment.web.CaptureRequest;
import dev.maestro.payment.web.CreatePaymentRequest;
import dev.maestro.payment.web.PaymentResponse;
import dev.maestro.payment.web.RefundRequest;
import dev.maestro.payment.web.RefundResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
 * <p>Every public method here is one database transaction that does three things
 * atomically: claims the idempotency key, applies the state change, and appends the
 * outbox event. Because all three commit together there is no ordering in which a crash
 * leaves the system inconsistent — no payment without its key, no state change without
 * the instruction it implies (ADR-0004, ADR-0013).
 *
 * <p>State changes are guarded conditional updates, never read-then-write. Two concurrent
 * captures both read {@code AUTHORIZED}; only one changes a row.
 */
@Service
public class PaymentService {

    private static final String ENDPOINT_CREATE = "POST /v1/payments";
    private static final String ENDPOINT_CONFIRM = "POST /v1/payments/{id}/confirm";
    private static final String ENDPOINT_CAPTURE = "POST /v1/payments/{id}/capture";
    private static final String ENDPOINT_VOID = "POST /v1/payments/{id}/void";
    private static final String ENDPOINT_REFUND = "POST /v1/payments/{id}/refunds";

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final IdempotencyRepository idempotency;
    private final PaymentCommands commands;
    private final ObjectMapper json;

    public PaymentService(
            PaymentRepository payments,
            RefundRepository refunds,
            IdempotencyRepository idempotency,
            PaymentCommands commands,
            ObjectMapper json) {
        this.payments = payments;
        this.refunds = refunds;
        this.idempotency = idempotency;
        this.commands = commands;
        this.json = json;
    }

    /** The result of an operation, and whether it was served from a previous one. */
    public record Result<T>(HttpStatus status, T body, boolean replayed) {
    }

    // --- create and confirm ------------------------------------------------

    @Transactional
    public Result<PaymentResponse> create(String idempotencyKey, CreatePaymentRequest request) {
        String merchantId = TenantContext.requireMerchantId();
        Money.parseCurrency(request.currency()); // rejects a bad code before anything is written

        Optional<StoredResponse> replay =
                claimOrReplay(merchantId, ENDPOINT_CREATE, idempotencyKey, fingerprint(request));
        if (replay.isPresent()) {
            return replay.get().as(PaymentResponse.class, json);
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
            commands.requestAuthorization(payment);
        }

        HttpStatus status = confirming ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        PaymentResponse body = toResponse(payment);
        complete(merchantId, ENDPOINT_CREATE, idempotencyKey, status, body, payment.id());
        return new Result<>(status, body, false);
    }

    @Transactional
    public Result<PaymentResponse> confirm(String idempotencyKey, String paymentId) {
        String merchantId = TenantContext.requireMerchantId();

        Optional<StoredResponse> replay =
                claimOrReplay(merchantId, ENDPOINT_CONFIRM, idempotencyKey, fingerprint(paymentId));
        if (replay.isPresent()) {
            return replay.get().as(PaymentResponse.class, json);
        }

        Payment payment = require(merchantId, paymentId);
        if (payments.transitionToAuthorizing(merchantId, paymentId) == 0) {
            throw ApiException.conflict(
                    "invalid_payment_state",
                    "A payment can only be confirmed from CREATED; this one is %s"
                            .formatted(payment.status()));
        }

        Payment authorizing = require(merchantId, paymentId);
        commands.requestAuthorization(authorizing);

        PaymentResponse body = toResponse(authorizing);
        complete(merchantId, ENDPOINT_CONFIRM, idempotencyKey, HttpStatus.ACCEPTED, body, paymentId);
        return new Result<>(HttpStatus.ACCEPTED, body, false);
    }

    // --- capture, void, refund ---------------------------------------------

    @Transactional
    public Result<PaymentResponse> capture(
            String idempotencyKey, String paymentId, CaptureRequest request) {
        String merchantId = TenantContext.requireMerchantId();

        Optional<StoredResponse> replay = claimOrReplay(
                merchantId, ENDPOINT_CAPTURE, idempotencyKey, fingerprint(paymentId, request));
        if (replay.isPresent()) {
            return replay.get().as(PaymentResponse.class, json);
        }

        Payment payment = require(merchantId, paymentId);
        long amountMinor = request.isFullCapture() ? payment.amountMinor() : request.amountMinor();
        if (amountMinor > payment.amountMinor()) {
            throw ApiException.unprocessable(
                    "capture_exceeds_authorized",
                    "Capture of %d exceeds the authorized %d %s"
                            .formatted(amountMinor, payment.amountMinor(), payment.currency()));
        }

        if (payments.transitionToCapturing(paymentId) == 0) {
            throw ApiException.conflict(
                    "invalid_payment_state",
                    "Only an AUTHORIZED payment can be captured; this one is %s"
                            .formatted(payment.status()));
        }

        commands.requestCapture(payment, amountMinor);

        PaymentResponse body = toResponse(require(merchantId, paymentId));
        complete(merchantId, ENDPOINT_CAPTURE, idempotencyKey, HttpStatus.ACCEPTED, body, paymentId);
        return new Result<>(HttpStatus.ACCEPTED, body, false);
    }

    @Transactional
    public Result<PaymentResponse> voidPayment(String idempotencyKey, String paymentId) {
        String merchantId = TenantContext.requireMerchantId();

        Optional<StoredResponse> replay =
                claimOrReplay(merchantId, ENDPOINT_VOID, idempotencyKey, fingerprint(paymentId));
        if (replay.isPresent()) {
            return replay.get().as(PaymentResponse.class, json);
        }

        Payment payment = require(merchantId, paymentId);
        if (payment.status() != PaymentStatus.AUTHORIZED) {
            throw ApiException.conflict(
                    "invalid_payment_state",
                    "Only an AUTHORIZED payment can be voided; this one is %s"
                            .formatted(payment.status()));
        }

        // The status stays AUTHORIZED until the acquirer confirms. A void that fails
        // leaves the authorization intact, and pretending otherwise would show the
        // merchant a released hold that the issuer still has.
        commands.requestVoid(payment);

        PaymentResponse body = toResponse(payment);
        complete(merchantId, ENDPOINT_VOID, idempotencyKey, HttpStatus.ACCEPTED, body, paymentId);
        return new Result<>(HttpStatus.ACCEPTED, body, false);
    }

    @Transactional
    public Result<RefundResponse> refund(
            String idempotencyKey, String paymentId, RefundRequest request) {
        String merchantId = TenantContext.requireMerchantId();

        Optional<StoredResponse> replay = claimOrReplay(
                merchantId, ENDPOINT_REFUND, idempotencyKey, fingerprint(paymentId, request));
        if (replay.isPresent()) {
            return replay.get().as(RefundResponse.class, json);
        }

        Payment payment = require(merchantId, paymentId);
        long amountMinor = request.isFullRefund()
                ? payment.refundableAmount().amountMinor()
                : request.amountMinor();
        if (amountMinor <= 0L) {
            throw ApiException.unprocessable(
                    "nothing_to_refund",
                    "This payment has no refundable balance remaining.");
        }

        // The invariant lives in this one guarded statement, not in a check above it:
        // two concurrent refunds both see room, and only one of them changes a row.
        if (payments.reserveRefund(merchantId, paymentId, amountMinor) == 0) {
            throw ApiException.unprocessable(
                    "refund_exceeds_captured",
                    "A refund of %d would exceed the refundable balance of %d %s"
                            .formatted(
                                    amountMinor,
                                    payment.refundableAmount().amountMinor(),
                                    payment.currency()));
        }

        RefundRepository.Refund refund = new RefundRepository.Refund(
                Ids.refund(),
                paymentId,
                merchantId,
                amountMinor,
                payment.currency(),
                request.reason(),
                RefundRepository.Refund.PENDING,
                null,
                null,
                null);
        refunds.insert(refund);
        commands.requestRefund(payment, refund);

        RefundResponse body = RefundResponse.from(refunds.find(merchantId, refund.id()).orElseThrow());
        complete(merchantId, ENDPOINT_REFUND, idempotencyKey, HttpStatus.ACCEPTED, body, refund.id());
        return new Result<>(HttpStatus.ACCEPTED, body, false);
    }

    // --- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentResponse get(String paymentId) {
        return toResponse(require(TenantContext.requireMerchantId(), paymentId));
    }

    @Transactional(readOnly = true)
    public RefundResponse getRefund(String refundId) {
        return refunds.find(TenantContext.requireMerchantId(), refundId)
                .map(RefundResponse::from)
                .orElseThrow(() -> ApiException.notFound("refund", refundId));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> refundsFor(String paymentId) {
        String merchantId = TenantContext.requireMerchantId();
        require(merchantId, paymentId);
        return refunds.findByPayment(merchantId, paymentId).stream()
                .map(RefundResponse::from)
                .toList();
    }

    // --- internals ---------------------------------------------------------

    private Payment require(String merchantId, String paymentId) {
        return payments.find(merchantId, paymentId)
                .orElseThrow(() -> ApiException.notFound("payment", paymentId));
    }

    /**
     * Claims the key, or returns the outcome of the request that already holds it.
     *
     * @return empty if the caller now owns the key and should perform the effect
     */
    private Optional<StoredResponse> claimOrReplay(
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
        return Optional.of(
                new StoredResponse(HttpStatus.valueOf(existing.responseStatus()), existing.responseBody()));
    }

    private void complete(
            String merchantId,
            String endpoint,
            String key,
            HttpStatus status,
            Object body,
            String resourceId) {
        idempotency.complete(
                merchantId, endpoint, key, status.value(), json.writeValueAsString(body), resourceId);
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
    private String fingerprint(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(json.writeValueAsString(part).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** A stored response, deserialised on replay into whatever the endpoint returns. */
    private record StoredResponse(HttpStatus status, String bodyJson) {

        <T> Result<T> as(Class<T> type, ObjectMapper json) {
            return new Result<>(status, json.readValue(bodyJson, type), true);
        }
    }
}
