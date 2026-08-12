package dev.maestro.payment.observability;

import dev.maestro.observability.MetricNames;
import dev.maestro.observability.MetricTags;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The payments funnel: one counter, tagged by transition.
 *
 * <p>Counted where the guarded update reports it changed a row, so a redelivered event
 * increments nothing — the funnel counts state changes, not messages. Requested-side
 * states ({@code CAPTURING} and friends) are deliberately absent: they can be entered
 * from two places and abandoned by failure, and a funnel stage that can double-count or
 * un-happen makes every ratio computed from it a lie. What is counted is what became
 * true.
 */
@Component
public class PaymentMetrics {

    /** Tag values for {@link MetricNames#PAYMENT_TRANSITIONS}. */
    public static final String CREATED = "created";
    public static final String AUTHORIZING = "authorizing";
    public static final String AUTHORIZED = "authorized";
    public static final String DECLINED = "declined";
    public static final String FAILED = "failed";
    public static final String CAPTURED = "captured";
    public static final String CAPTURE_FAILED = "capture_failed";
    public static final String VOIDED = "voided";
    public static final String REFUND_SUCCEEDED = "refund_succeeded";
    public static final String REFUND_FAILED = "refund_failed";
    public static final String EXPIRED = "expired";

    private final MeterRegistry meters;

    public PaymentMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    public void transition(String transition) {
        Counter.builder(MetricNames.PAYMENT_TRANSITIONS)
                .description("Payment state transitions that actually changed a row")
                .tag(MetricTags.TRANSITION, transition)
                .register(meters)
                .increment();
    }
}
