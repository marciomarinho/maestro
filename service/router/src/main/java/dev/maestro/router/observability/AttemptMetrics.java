package dev.maestro.router.observability;

import dev.maestro.observability.MetricNames;
import dev.maestro.observability.MetricTags;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Counts every settled acquirer attempt, as events rather than levels.
 *
 * <p>{@link RoutingMetrics} publishes what the router <em>believes</em> — decayed
 * rates that answer "how healthy does this corridor look right now". This class
 * publishes what <em>happened</em>: raw attempt counts an operator can sum, rate and
 * ratio over any window after the fact. The routing split panel is built on these,
 * because a share of traffic is a question about events, not about opinions.
 */
@Component
public class AttemptMetrics {

    private final MeterRegistry meters;

    public AttemptMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    public void record(
            String acquirerId, String corridor, String operation, String outcome, long latencyMs) {
        Counter.builder(MetricNames.ROUTER_ATTEMPTS)
                .description("Settled acquirer attempts")
                .tag(MetricTags.ACQUIRER, acquirerId)
                .tag(MetricTags.CORRIDOR, corridor)
                .tag(MetricTags.OPERATION, operation.toLowerCase(Locale.ROOT))
                .tag(MetricTags.OUTCOME, outcome.toLowerCase(Locale.ROOT))
                .register(meters)
                .increment();

        // Outcome is deliberately not a timer tag: latency percentiles sliced five ways
        // per acquirer are mostly empty buckets, and the question this answers is "how
        // slow is this acquirer for this operation", not "how slow are its declines".
        Timer.builder(MetricNames.ROUTER_ACQUIRER_LATENCY)
                .description("Acquirer call latency as the router experienced it")
                .tag(MetricTags.ACQUIRER, acquirerId)
                .tag(MetricTags.OPERATION, operation.toLowerCase(Locale.ROOT))
                .register(meters)
                .record(Duration.ofMillis(latencyMs));
    }
}
