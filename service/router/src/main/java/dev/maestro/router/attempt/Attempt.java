package dev.maestro.router.attempt;

import java.math.BigDecimal;

/**
 * One call to one acquirer.
 *
 * <p>{@code attemptNo} is not bookkeeping: it is the input to the acquirer-facing
 * idempotency key. A retry of the <em>same</em> attempt reuses the key so the acquirer
 * returns its original answer; a failover to a different acquirer increments it,
 * because that is a genuinely different operation against a different institution
 * (ADR-0006).
 *
 * <p>{@code healthScore} is the score the chosen acquirer held at the instant it was
 * chosen, frozen. It is the difference between "why did this payment go to Northbank?"
 * being a query and being an investigation — by the time anyone asks, the live score will
 * have moved, and the live score is not the one the decision was made on.
 */
public record Attempt(
        String id,
        String paymentId,
        String merchantId,
        int attemptNo,
        String operation,
        String acquirerId,
        String corridor,
        String selectionReason,
        BigDecimal healthScore,
        String outcome,
        String responseCode,
        String responseMessage,
        Integer latencyMs,
        String acquirerReference,
        boolean finalAttempt) {

    public static final String OPERATION_AUTHORIZE = "AUTHORIZE";
    public static final String OPERATION_CAPTURE = "CAPTURE";
    public static final String OPERATION_REFUND = "REFUND";
    public static final String OPERATION_VOID = "VOID";

    public static final String OUTCOME_IN_FLIGHT = "IN_FLIGHT";
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_DECLINED_BUSINESS = "DECLINED_BUSINESS";
    public static final String OUTCOME_DECLINED_TECHNICAL = "DECLINED_TECHNICAL";
    public static final String OUTCOME_TIMEOUT = "TIMEOUT";
    public static final String OUTCOME_THROTTLED = "THROTTLED";

    public boolean isInFlight() {
        return OUTCOME_IN_FLIGHT.equals(outcome);
    }

    /** The key sent to the acquirer, so a retry within this attempt cannot double-charge. */
    public String acquirerIdempotencyKey() {
        return paymentId + ":" + operation + ":" + attemptNo;
    }
}
