package dev.maestro.acquirersim.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Returns captured funds to the cardholder. */
public record RefundRequest(
        @NotBlank String refundId,
        @NotBlank String paymentId,
        @NotBlank String acquirerReference,
        @Positive long amountMinor,
        @NotBlank String currency) {
}
