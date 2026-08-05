package dev.maestro.acquirersim.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Takes funds an authorization reserved. May be for less than was authorized. */
public record CaptureRequest(
        @NotBlank String paymentId,
        @NotBlank String acquirerReference,
        @Positive long amountMinor,
        @NotBlank String currency) {
}
