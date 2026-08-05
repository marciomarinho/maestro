package dev.maestro.acquirersim.api;

import jakarta.validation.constraints.NotBlank;

/** Releases an authorization before it is captured. */
public record VoidRequest(
        @NotBlank String paymentId,
        @NotBlank String acquirerReference) {
}
