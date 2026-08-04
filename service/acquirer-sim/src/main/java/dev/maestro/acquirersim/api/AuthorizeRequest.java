package dev.maestro.acquirersim.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * What the platform sends an acquirer to obtain an authorization.
 *
 * <p>Note what is absent: there is no field that could carry a card number. The
 * platform holds tokens only, which is what keeps it outside PCI scope.
 */
public record AuthorizeRequest(
        @NotBlank String paymentId,
        @NotBlank String merchantId,
        @Positive long amountMinor,
        @NotBlank String currency,
        @NotBlank String cardToken,
        @NotBlank String cardNetwork) {
}
