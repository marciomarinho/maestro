package dev.maestro.outbox;

import dev.maestro.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Watches the queue the relay is supposed to be draining.
 *
 * <p>Two numbers, because they fail differently. A large <em>pending</em> count with a
 * young oldest row is a burst the relay is working through; a small count whose oldest
 * row keeps ageing is a relay that has stalled — crashed scheduler, poisoned batch, an
 * aggregate whose advisory lock never releases. The <em>oldest-age</em> gauge is the
 * one the runbook alerts on, because it is the one that distinguishes "busy" from
 * "stuck".
 *
 * <p>Sampled on a timer rather than read live at scrape: the numbers come from a query,
 * and a Prometheus scrape should never be the thing holding a database connection.
 */
public class OutboxMetrics {

    private final JdbcClient jdbc;
    private final OutboxProperties properties;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxMetrics(JdbcClient jdbc, OutboxProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;

        Gauge.builder(MetricNames.OUTBOX_PENDING, pending, AtomicLong::get)
                .description("Outbox rows not yet published")
                .register(meters);
        Gauge.builder(MetricNames.OUTBOX_OLDEST_AGE, oldestAgeSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished outbox row. The stalled-relay signal.")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${maestro.outbox.metrics-interval:10s}",
            initialDelayString = "${maestro.outbox.metrics-interval:10s}")
    public void sample() {
        jdbc.sql("""
                SELECT count(*) AS pending,
                       COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::bigint AS oldest_age
                  FROM %s.outbox_event
                 WHERE published_at IS NULL
                """.formatted(properties.schema()))
                .query((rs, rowNum) -> {
                    pending.set(rs.getLong("pending"));
                    oldestAgeSeconds.set(rs.getLong("oldest_age"));
                    return Boolean.TRUE; // single() rejects a null-mapped row
                })
                .single();
    }
}
