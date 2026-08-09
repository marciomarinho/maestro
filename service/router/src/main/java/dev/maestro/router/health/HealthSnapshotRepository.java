package dev.maestro.router.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Health, written down.
 *
 * <p>Advisory only, and worth being precise about why it exists. A router that restarts
 * during an incident would otherwise come back believing every acquirer is fine, and send
 * a burst of traffic straight into the one it had spent the last two minutes learning to
 * avoid — at the worst possible moment, because the reason it restarted may well be the
 * same incident.
 *
 * <p>It is explicitly not shared state. Instances converge independently (ADR-0007), and
 * a row written by another instance is read as a starting hint that live evidence
 * overwrites within seconds. Making this authoritative would turn a per-instance
 * observation into a distributed consensus problem, and buy nothing: the thing being
 * agreed on changes faster than agreement could be reached.
 */
@Repository
public class HealthSnapshotRepository {

    private final JdbcClient jdbc;

    public HealthSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Snapshot> loadAll() {
        return jdbc.sql("""
                SELECT acquirer_id, corridor, approval_rate, technical_failure_rate,
                       latency_ms, samples, observed_at
                  FROM corridor_health_snapshot
                """)
                .query((rs, rowNum) -> new Snapshot(
                        new CorridorKey(rs.getString("acquirer_id"), rs.getString("corridor")),
                        rs.getDouble("approval_rate"),
                        rs.getDouble("technical_failure_rate"),
                        rs.getDouble("latency_ms"),
                        rs.getDouble("samples"),
                        rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Writes the current view, replacing whatever was there.
     *
     * <p>Last writer wins, with no coordination, which is the correct behaviour for
     * advisory data: two instances disagreeing about an acquirer's health are both right
     * about the traffic they each saw, and neither answer is worth locking for.
     */
    public void save(Map<CorridorKey, CorridorHealth.Reading> readings, Instant observedAt) {
        readings.forEach((key, reading) -> jdbc.sql("""
                INSERT INTO corridor_health_snapshot
                    (acquirer_id, corridor, approval_rate, technical_failure_rate,
                     latency_ms, samples, observed_at)
                VALUES (:acquirerId, :corridor, :approvalRate, :technicalFailureRate,
                        :latencyMs, :samples, :observedAt)
                ON CONFLICT (acquirer_id, corridor) DO UPDATE
                   SET approval_rate = EXCLUDED.approval_rate,
                       technical_failure_rate = EXCLUDED.technical_failure_rate,
                       latency_ms = EXCLUDED.latency_ms,
                       samples = EXCLUDED.samples,
                       observed_at = EXCLUDED.observed_at
                """)
                .param("acquirerId", key.acquirerId())
                .param("corridor", key.corridor())
                .param("approvalRate", rate(reading.approvalRate()))
                .param("technicalFailureRate", rate(reading.technicalFailureRate()))
                .param("latencyMs", BigDecimal.valueOf(reading.latencyMillis().orElse(0)))
                .param("samples", (long) reading.samples())
                .param("observedAt", java.time.OffsetDateTime.ofInstant(
                        observedAt, java.time.ZoneOffset.UTC))
                .update());
    }

    /** Clamps to the range the column's check constraint allows. */
    private static BigDecimal rate(double value) {
        return BigDecimal.valueOf(Math.clamp(value, 0, 1));
    }

    /**
     * A persisted opinion.
     *
     * @param observedAt when it was written, so a snapshot older than the half-life can be
     *                   discarded rather than resurrected — evidence that has fully decayed
     *                   is not evidence
     */
    public record Snapshot(
            CorridorKey key,
            double approvalRate,
            double technicalFailureRate,
            double latencyMillis,
            double samples,
            Instant observedAt) {
    }
}
