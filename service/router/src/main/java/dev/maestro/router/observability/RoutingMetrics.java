package dev.maestro.router.observability;

import dev.maestro.router.health.CorridorKey;
import dev.maestro.router.health.HealthRegistry;
import dev.maestro.router.resilience.CircuitBreakers;
import dev.maestro.router.resilience.RetryBudget;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes what the router currently believes, so it can be watched rather than inferred.
 *
 * <p>The brownout demo is the reason this exists in Phase 3 rather than waiting for the
 * dashboards in Phase 4. A demo where an acquirer degrades and traffic silently moves is a
 * demo where the audience has to take the interesting part on trust; the same demo with
 * the health score visibly collapsing and the breaker tripping is the one worth running.
 *
 * <p>Gauges are registered lazily, because corridors appear as traffic discovers them —
 * there is no list of every acquirer-corridor pair to enumerate at startup, and inventing
 * one would mean reporting zeros for combinations that will never carry a payment.
 */
@Component
public class RoutingMetrics {

    private final MeterRegistry meters;
    private final HealthRegistry health;
    private final CircuitBreakers breakers;
    private final RetryBudget retryBudget;
    private final Set<CorridorKey> registered = ConcurrentHashMap.newKeySet();

    public RoutingMetrics(
            MeterRegistry meters,
            HealthRegistry health,
            CircuitBreakers breakers,
            RetryBudget retryBudget) {
        this.meters = meters;
        this.health = health;
        this.breakers = breakers;
        this.retryBudget = retryBudget;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerBudgetGauge() {
        Gauge.builder("maestro.router.retry.budget.utilisation", retryBudget, RetryBudget::utilisation)
                .description("Retries spent as a fraction of the ceiling. Approaching one "
                        + "means failover is about to start being refused.")
                .register(meters);
    }

    /**
     * Registers a gauge per corridor the moment it first has an opinion.
     *
     * <p>Polled rather than pushed on each attempt: these are levels, not events, and a
     * gauge that reads the live value when scraped cannot drift from the number the router
     * is actually deciding on.
     */
    @Scheduled(fixedDelay = 5_000)
    public void discoverCorridors() {
        health.readAll().keySet().forEach(this::registerIfNew);
    }

    private void registerIfNew(CorridorKey key) {
        if (!registered.add(key)) {
            return;
        }
        Tags tags = Tags.of("acquirer", key.acquirerId(), "corridor", key.corridor());

        Gauge.builder("maestro.router.corridor.approval.rate", key,
                        k -> health.readingFor(k).approvalRate())
                .description("Approvals over decisive outcomes, shrunk towards the prior")
                .tags(tags)
                .register(meters);

        Gauge.builder("maestro.router.corridor.technical.failure.rate", key,
                        k -> health.readingFor(k).technicalFailureRate())
                .description("Failures over all attempts. The availability signal.")
                .tags(tags)
                .register(meters);

        Gauge.builder("maestro.router.corridor.latency", key,
                        k -> health.readingFor(k).latencyMillis().orElse(0))
                .description("Time-decayed mean acquirer latency")
                .baseUnit("milliseconds")
                .tags(tags)
                .register(meters);

        Gauge.builder("maestro.router.corridor.samples", key,
                        k -> health.readingFor(k).samples())
                .description("Effective observations behind the reading. Falling towards "
                        + "zero means the router is deciding on assumption, not evidence.")
                .tags(tags)
                .register(meters);

        // Numeric because a breaker's state has an order — closed, half-open, open — and
        // an alert wants "not closed" rather than a string comparison per state.
        Gauge.builder("maestro.router.corridor.breaker", key,
                        k -> switch (breakers.stateOf(k)) {
                            case CLOSED -> 0;
                            case HALF_OPEN -> 1;
                            case OPEN -> 2;
                        })
                .description("0 closed, 1 half-open, 2 open")
                .tags(tags)
                .register(meters);
    }

    /** Everything at once, for the ops endpoint and the demo. */
    public Map<CorridorKey, CircuitBreakers.State> breakerStates() {
        return breakers.states();
    }
}
