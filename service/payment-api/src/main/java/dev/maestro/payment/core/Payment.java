package dev.maestro.payment.core;

import dev.maestro.domain.money.Money;
import dev.maestro.domain.payment.CaptureMethod;
import dev.maestro.domain.payment.PaymentStatus;
import java.time.Instant;

/** A payment as stored. Amounts travel as minor units and always with their currency. */
public record Payment(
        String id,
        String merchantId,
        long amountMinor,
        String currency,
        long capturedAmountMinor,
        long refundedAmountMinor,
        // Refunds requested but not yet confirmed. Reserving up front is what stops two
        // concurrent refunds from together exceeding what was captured.
        long refundReservedMinor,
        String cardToken,
        String cardNetwork,
        String cardLast4,
        String cardCountry,
        PaymentStatus status,
        CaptureMethod captureMethod,
        String reference,
        String metadataJson,
        String acquirerId,
        String acquirerReference,
        String authorizationCode,
        String declineCode,
        String failureReason,
        Instant authorizedAt,
        Instant createdAt,
        Instant updatedAt) {

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    public Money capturedAmount() {
        return Money.of(capturedAmountMinor, currency);
    }

    public Money refundedAmount() {
        return Money.of(refundedAmountMinor, currency);
    }

    /** What may still be refunded: captured, less anything already refunded or in flight. */
    public Money refundableAmount() {
        return Money.of(capturedAmountMinor - refundReservedMinor, currency);
    }
}
