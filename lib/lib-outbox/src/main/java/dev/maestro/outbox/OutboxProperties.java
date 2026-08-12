package dev.maestro.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param schema        the owning service's schema; the outbox table always lives
 *                      beside the data whose transaction it shares
 * @param pollInterval  safety-net polling interval. Normal latency comes from the
 *                      after-commit wake-up, not from this
 * @param batchSize     rows claimed per relay pass
 * @param retention     how long published rows are kept before sweeping
 */
@ConfigurationProperties("maestro.outbox")
public record OutboxProperties(
        String schema,
        Duration pollInterval,
        int batchSize,
        Duration retention) {

    public OutboxProperties {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("maestro.outbox.schema must be set");
        }
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        retention = retention == null ? Duration.ofDays(7) : retention;
    }
}
