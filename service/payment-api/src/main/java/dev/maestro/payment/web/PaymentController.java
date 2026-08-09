package dev.maestro.payment.web;

import dev.maestro.payment.attempt.AttemptProjection;
import dev.maestro.payment.core.PaymentService;
import dev.maestro.payment.security.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final PaymentService payments;
    private final AttemptProjection attempts;

    public PaymentController(PaymentService payments, AttemptProjection attempts) {
        this.payments = payments;
        this.attempts = attempts;
    }

    /**
     * The idempotency key is <strong>required</strong> on every state-changing request, not
     * optional. Making it optional invites merchants to omit it on precisely the endpoints
     * where a duplicate costs a customer money.
     */
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return respond(payments.create(requireIdempotencyKey(idempotencyKey), request));
    }

    @PostMapping("/payments/{paymentId}/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paymentId) {
        return respond(payments.confirm(requireIdempotencyKey(idempotencyKey), paymentId));
    }

    @PostMapping("/payments/{paymentId}/capture")
    public ResponseEntity<PaymentResponse> capture(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paymentId,
            @Valid @RequestBody(required = false) CaptureRequest request) {
        return respond(payments.capture(
                requireIdempotencyKey(idempotencyKey),
                paymentId,
                request == null ? new CaptureRequest(null) : request));
    }

    @PostMapping("/payments/{paymentId}/void")
    public ResponseEntity<PaymentResponse> voidPayment(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paymentId) {
        return respond(payments.voidPayment(requireIdempotencyKey(idempotencyKey), paymentId));
    }

    /**
     * Refunding is a separate operation from taking a payment because it moves money
     * outward. From Phase 6 it also carries its own permission, so a key that can take
     * payments cannot automatically return them.
     */
    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refund(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paymentId,
            @Valid @RequestBody(required = false) RefundRequest request) {
        return respond(payments.refund(
                requireIdempotencyKey(idempotencyKey),
                paymentId,
                request == null ? new RefundRequest(null, null) : request));
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentResponse get(@PathVariable String paymentId) {
        return payments.get(paymentId);
    }

    /**
     * The routing audit trail: which acquirers were tried, why each was chosen, and what
     * each answered.
     *
     * <p>Unusual for a payments API, and deliberate. Merchants integrating with
     * orchestration platforms are routinely unable to find out why a payment took the path
     * it took, which makes the platform a black box exactly when someone is trying to
     * explain a bad afternoon to their own management. Exposing the selection reason and
     * the score the decision was made on turns that into something a support team can read.
     *
     * <p>Reading {@code get} first is not redundant: it is what makes an unknown payment,
     * and another tenant's payment, both return 404 rather than an empty list that implies
     * the payment exists and simply has no history (ADR-0009).
     */
    @GetMapping("/payments/{paymentId}/attempts")
    public List<AttemptProjection.AttemptView> attemptsFor(@PathVariable String paymentId) {
        payments.get(paymentId);
        return attempts.forPayment(TenantContext.requireMerchantId(), paymentId);
    }

    @GetMapping("/payments/{paymentId}/refunds")
    public List<RefundResponse> refundsFor(@PathVariable String paymentId) {
        return payments.refundsFor(paymentId);
    }

    @GetMapping("/refunds/{refundId}")
    public RefundResponse getRefund(@PathVariable String refundId) {
        return payments.getRefund(refundId);
    }

    private static <T> ResponseEntity<T> respond(PaymentService.Result<T> result) {
        return ResponseEntity.status(result.status())
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest(
                    "idempotency_key_required",
                    "An Idempotency-Key header is required on every state-changing request.");
        }
        return idempotencyKey;
    }
}
