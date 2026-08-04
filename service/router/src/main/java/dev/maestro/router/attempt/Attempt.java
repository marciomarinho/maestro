package dev.maestro.router.attempt;

/**
 * One call to one acquirer.
 *
 * <p>{@code attemptNo} is not bookkeeping: it is the input to the acquirer-facing
 * idempotency key. A retry of the <em>same</em> attempt reuses the key so the acquirer
 * returns its original answer; a failover to a different acquirer increments it,
 * because that is a genuinely different operation against a different institution
 * (ADR-0006).
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
        String outcome,
        String responseCode,
        String responseMessage,
        Integer latencyMs,
        String acquirerReference) {

    public static final String OPERATION_AUTHORIZE = "AUTHORIZE";
    public static final String OUTCOME_IN_FLIGHT = "IN_FLIGHT";

    public boolean isInFlight() {
        return OUTCOME_IN_FLIGHT.equals(outcome);
    }

    /** The key sent to the acquirer, so a retry within this attempt cannot double-charge. */
    public String acquirerIdempotencyKey() {
        return paymentId + ":" + operation + ":" + attemptNo;
    }
}
