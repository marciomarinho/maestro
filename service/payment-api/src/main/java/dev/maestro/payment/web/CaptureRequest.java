package dev.maestro.payment.web;

import jakarta.validation.constraints.Positive;

/**
 * @param amountMinor how much of the authorization to take; omit to capture it all.
 *                    Capturing less than was authorized is normal — a shipped order can
 *                    be smaller than the one that was placed — and the remainder is
 *                    released rather than held
 */
public record CaptureRequest(@Positive(message = "amount_minor must be greater than zero")
                             Long amountMinor) {

    public boolean isFullCapture() {
        return amountMinor == null;
    }
}
