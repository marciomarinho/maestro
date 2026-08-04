package dev.maestro.payment.web;

import java.time.Instant;
import java.util.Map;

/** The merchant-facing representation of a payment. */
public record PaymentResponse(
        String id,
        String status,
        long amountMinor,
        String currency,
        long capturedAmountMinor,
        long refundedAmountMinor,
        Card card,
        String reference,
        Map<String, String> metadata,
        String acquirerId,
        String acquirerReference,
        String declineCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    /** Only non-sensitive card metadata is ever exposed, because only that is ever held. */
    public record Card(String network, String last4, String country) {
    }
}
