package dev.maestro.router.health;

import dev.maestro.router.RouterProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Carries health across a restart.
 *
 * <p>Reads the last snapshot once the application is up, and writes the current view on a
 * schedule. Both halves are best-effort: a failure here degrades the router to starting
 * blind, which is exactly where it would be without the table, so it must never prevent
 * the service from serving traffic.
 *
 * <p>Restoring after startup rather than before first use is a deliberate choice about
 * which risk to take. The alternative — blocking the first authorization until the
 * snapshot is loaded — trades a certain, small cost (a few requests routed on priors)
 * for an uncertain, large one (every payment waiting on a database round trip during the
 * exact window when the database may be the thing that is unwell).
 */
@Component
public class HealthSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(HealthSnapshotJob.class);

    private final HealthRegistry registry;
    private final HealthSnapshotRepository snapshots;
    private final RouterProperties.Health config;
    private final Clock clock;

    public HealthSnapshotJob(
            HealthRegistry registry,
            HealthSnapshotRepository snapshots,
            RouterProperties properties,
            Clock clock) {
        this.registry = registry;
        this.snapshots = snapshots;
        this.config = properties.health();
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        // Beyond a few half-lives a snapshot has decayed to nothing anyway; restoring it
        // would resurrect an opinion that live traffic had already been entitled to forget.
        Duration keepFor = config.halfLife().multipliedBy(4);
        Instant cutoff = clock.instant().minus(keepFor);

        try {
            long restored = snapshots.loadAll().stream()
                    .filter(snapshot -> snapshot.observedAt().isAfter(cutoff))
                    .peek(snapshot -> registry.restore(
                            snapshot.key(),
                            snapshot.approvalRate(),
                            snapshot.technicalFailureRate(),
                            snapshot.latencyMillis(),
                            snapshot.samples()))
                    .count();
            log.info("Restored health for {} corridors from snapshot", restored);
        } catch (RuntimeException e) {
            log.warn("Could not restore health snapshot; starting from priors", e);
        }
    }

    @Scheduled(
            initialDelayString = "${maestro.router.health.snapshot-interval:10s}",
            fixedDelayString = "${maestro.router.health.snapshot-interval:10s}")
    public void persist() {
        try {
            snapshots.save(registry.readAll(), clock.instant());
        } catch (RuntimeException e) {
            log.warn("Could not persist health snapshot", e);
        }
    }
}
