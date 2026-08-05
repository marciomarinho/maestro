package dev.maestro.events.payload;

/** The authorization was released. The ledger releases its hold and posts nothing. */
public record VoidSucceeded(
        String paymentId,
        String merchantId,
        String acquirerId,
        int attemptNo) {
}
