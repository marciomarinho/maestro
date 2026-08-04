package dev.maestro.payment.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * @param amountMinor   an integer count of minor units; there is deliberately no field
 *                      called {@code amount}, because a client would guess at its unit
 * @param cardToken     an opaque token. No field in this API accepts a card number
 * @param captureMethod {@code AUTOMATIC} or {@code MANUAL}; defaults to automatic
 * @param confirm       submit for authorization immediately, saving a round trip
 */
public record CreatePaymentRequest(
        @Positive(message = "amount_minor must be greater than zero")
        long amountMinor,

        @NotBlank
        @Size(min = 3, max = 3, message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @NotBlank
        @Pattern(
                regexp = "tok_[a-z]+_[0-9]{4}",
                message = "card_token must be a vault token of the form tok_<network>_<last4>")
        String cardToken,

        String reference,
        String captureMethod,
        boolean confirm,
        Map<String, String> metadata) {

    public CreatePaymentRequest {
        captureMethod = captureMethod == null || captureMethod.isBlank()
                ? "AUTOMATIC"
                : captureMethod.toUpperCase();
        currency = currency == null ? null : currency.toUpperCase();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
