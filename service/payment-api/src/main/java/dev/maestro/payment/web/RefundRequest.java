package dev.maestro.payment.web;

import jakarta.validation.constraints.Positive;

/**
 * @param amountMinor how much to return; omit or pass zero to refund everything still
 *                    refundable, which is the common case and saves the merchant having to
 *                    compute a remainder that the platform already knows
 */
public record RefundRequest(@Positive(message = "amount_minor must be greater than zero")
                            Long amountMinor,
                            String reason) {

    public boolean isFullRefund() {
        return amountMinor == null;
    }
}
