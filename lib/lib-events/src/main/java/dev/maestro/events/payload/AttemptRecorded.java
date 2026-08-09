package dev.maestro.events.payload;

import java.math.BigDecimal;

/**
 * One acquirer call, and why it was made.
 *
 * <p>Published for <em>every</em> attempt, not only the one that settled the operation.
 * That is the entire point: a payment that succeeded on its second acquirer has a story,
 * and the interesting half of it is the attempt that failed. An event stream carrying
 * only outcomes would show a payment authorized at Northbank and no trace of the ninety
 * seconds Southcross spent timing out first.
 *
 * <p>This is the only event in the platform whose purpose is explanation rather than
 * state. Nothing transitions because of it; payment-api projects it so that
 * {@code GET /v1/payments/{id}/attempts} can answer "why did this payment go there?"
 * without the merchant-facing service having to reach into the router's schema
 * (ADR-0014, ADR-0017).
 *
 * @param selectionReason {@code BEST_SCORE}, {@code EXPLORATION}, {@code FAILOVER} or
 *                        {@code PINNED}
 * @param healthScore     the score the acquirer held at the instant it was chosen, frozen.
 *                        Null for operations that were pinned rather than routed, because
 *                        no score was consulted — recording the live one would imply a
 *                        decision that never happened
 * @param finalAttempt    whether this attempt ended the operation
 */
public record AttemptRecorded(
        String paymentId,
        String merchantId,
        String operation,
        int attemptNo,
        String acquirerId,
        String corridor,
        String selectionReason,
        BigDecimal healthScore,
        String outcome,
        String responseCode,
        String responseMessage,
        long latencyMs,
        boolean finalAttempt) {
}
