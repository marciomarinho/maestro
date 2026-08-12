package dev.maestro.observability;

import org.slf4j.MDC;

/**
 * Puts the payment correlation fields on the logging context for a scope.
 *
 * <p>Structured log lines carry {@code payment_id} and {@code merchant_id} so an
 * operator can follow one payment across services with a single filter; the tracing
 * bridge contributes {@code trace_id} on its own. Scoped and self-removing, because MDC
 * is thread-local state and both platform threads (Kafka listeners) and virtual threads
 * (HTTP requests) are reused — a field left behind attributes the next payment's logs
 * to this one.
 *
 * <pre>{@code
 * try (var ignored = LogContext.forPayment(paymentId, merchantId)) {
 *     // every log line in here carries both fields
 * }
 * }</pre>
 */
public final class LogContext implements AutoCloseable {

    public static final String PAYMENT_ID = "payment_id";
    public static final String MERCHANT_ID = "merchant_id";

    private LogContext(String paymentId, String merchantId) {
        if (paymentId != null) {
            MDC.put(PAYMENT_ID, paymentId);
        }
        if (merchantId != null) {
            MDC.put(MERCHANT_ID, merchantId);
        }
    }

    public static LogContext forPayment(String paymentId, String merchantId) {
        return new LogContext(paymentId, merchantId);
    }

    @Override
    public void close() {
        MDC.remove(PAYMENT_ID);
        MDC.remove(MERCHANT_ID);
    }
}
