package dev.maestro.payment.web;

import dev.maestro.payment.core.RefundRepository;
import java.time.Instant;

public record RefundResponse(
        String id,
        String paymentId,
        String status,
        long amountMinor,
        String currency,
        String reason,
        String acquirerReference,
        String failureReason,
        Instant createdAt) {

    public static RefundResponse from(RefundRepository.Refund refund) {
        return new RefundResponse(
                refund.id(),
                refund.paymentId(),
                refund.status(),
                refund.amountMinor(),
                refund.currency(),
                refund.reason(),
                refund.acquirerReference(),
                refund.failureReason(),
                refund.createdAt());
    }
}
