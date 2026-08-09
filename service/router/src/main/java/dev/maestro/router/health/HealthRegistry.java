package dev.maestro.router.health;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.router.RouterProperties;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Everything the router believes about every acquirer, on every corridor.
 *
 * <p>State is per process and held in memory. That is a deliberate limit rather than an
 * omission (ADR-0007): each instance forms its opinion from the traffic it actually sent,
 * which is the traffic its opinion will govern. Sharing health across instances would
 * make every routing decision depend on a network round trip to a store holding a number
 * that changes several times a second — the coordination would cost more than the
 * disagreement it removed.
 *
 * <p>Deliberately free of anything that talks to a database or a broker, so the
 * behaviour that matters — how fast an opinion moves, and how far — can be tested with a
 * clock the test controls and no infrastructure at all. Persistence lives in
 * {@link HealthSnapshotJob}, on the other side of this seam.
 */
@Component
public class HealthRegistry {

    private final RouterProperties.Health config;
    private final Clock clock;
    private final ConcurrentHashMap<CorridorKey, CorridorHealth> corridors = new ConcurrentHashMap<>();

    public HealthRegistry(RouterProperties properties, Clock clock) {
        this.config = properties.health();
        this.clock = clock;
    }

    /**
     * Folds one completed attempt into the corridor's health.
     *
     * <p>Exhaustive over the sealed outcome type, so adding a way for an acquirer call to
     * end forces a decision here about what it means for that acquirer's reputation.
     * Getting this mapping wrong is subtle and expensive: count declines as failures and
     * the router chases the merchant's least risky traffic; count timeouts as declines and
     * an outage reads as an issuer tightening up.
     */
    public void record(CorridorKey key, AcquirerOutcome outcome, long latencyMillis) {
        long now = clock.millis();
        CorridorHealth health = corridors.computeIfAbsent(key, k -> new CorridorHealth(config));
        switch (outcome) {
            case AcquirerOutcome.Approved ignored -> health.recordApproval(now, latencyMillis);
            case AcquirerOutcome.BusinessDecline ignored ->
                    health.recordBusinessDecline(now, latencyMillis);
            case AcquirerOutcome.TechnicalFailure ignored ->
                    health.recordTechnicalFailure(now, latencyMillis);
            case AcquirerOutcome.Timeout ignored -> health.recordTechnicalFailure(now, latencyMillis);
            case AcquirerOutcome.Throttled ignored -> health.recordThrottled(now);
        }
    }

    /**
     * Folds in an operation that followed an authorization.
     *
     * <p>Captures, refunds and voids are not routed — they go to the institution holding
     * the authorization — but they are still evidence about whether that institution is
     * reachable, and throwing that away would mean a router blind to an outage on every
     * corridor whose traffic happens to be mid-lifecycle. What they are <em>not</em> is
     * evidence about approval rate, so they never touch it.
     */
    public void recordFollowUp(CorridorKey key, AcquirerOutcome outcome, long latencyMillis) {
        long now = clock.millis();
        CorridorHealth health = corridors.computeIfAbsent(key, k -> new CorridorHealth(config));
        switch (outcome) {
            case AcquirerOutcome.Approved ignored -> health.recordReachable(now, latencyMillis);
            case AcquirerOutcome.BusinessDecline ignored -> health.recordReachable(now, latencyMillis);
            case AcquirerOutcome.TechnicalFailure ignored ->
                    health.recordTechnicalFailure(now, latencyMillis);
            case AcquirerOutcome.Timeout ignored -> health.recordTechnicalFailure(now, latencyMillis);
            case AcquirerOutcome.Throttled ignored -> health.recordThrottled(now);
        }
    }

    /**
     * The current view of one corridor.
     *
     * <p>A corridor never seen before answers with the prior rather than with nothing, so
     * a newly configured acquirer is a candidate from its first request instead of waiting
     * for evidence it cannot receive until it is a candidate.
     */
    public CorridorHealth.Reading readingFor(CorridorKey key) {
        return corridors
                .computeIfAbsent(key, k -> new CorridorHealth(config))
                .readAt(clock.millis());
    }

    /** Every corridor with a view, for metrics, the ops endpoint and the snapshot. */
    public Map<CorridorKey, CorridorHealth.Reading> readAll() {
        long now = clock.millis();
        return corridors.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().readAt(now)));
    }

    /** Adopts a persisted opinion. See {@link HealthSnapshotRepository} for why it is a hint. */
    public void restore(
            CorridorKey key,
            double approvalRate,
            double technicalFailureRate,
            double latencyMillis,
            double samples) {
        corridors
                .computeIfAbsent(key, k -> new CorridorHealth(config))
                .restore(clock.millis(), approvalRate, technicalFailureRate, latencyMillis, samples);
    }
}
