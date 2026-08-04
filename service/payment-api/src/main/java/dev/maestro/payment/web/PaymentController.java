package dev.maestro.payment.web;

import dev.maestro.payment.core.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * The idempotency key is <strong>required</strong>, not optional. Making it
     * optional invites merchants to omit it on precisely the endpoints where a
     * duplicate costs a customer money.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return respond(payments.create(requireIdempotencyKey(idempotencyKey), request));
    }

    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paymentId) {
        return respond(payments.confirm(requireIdempotencyKey(idempotencyKey), paymentId));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable String paymentId) {
        return payments.get(paymentId);
    }

    private static ResponseEntity<PaymentResponse> respond(PaymentService.Result result) {
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
