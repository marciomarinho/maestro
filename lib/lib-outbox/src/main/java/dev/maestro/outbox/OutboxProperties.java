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
 * @param topicPartitions how many partitions the platform's topics are created with.
 *                      Not decoration: ADR-0005 partitions by payment so that one slow
 *                      payment cannot pin a consumer thread, and a topic with a single
 *                      partition silently repeals that decision. Six is enough to keep
 *                      the local stack parallel without making the broker's memory
 *                      footprint noticeable on a laptop
 * @param topicReplicas replication factor for those topics. One, because everything here
 *                      runs on a single local broker (ADR-0010); a real deployment
 *                      provisions its topics outside the application
 */
@ConfigurationProperties("maestro.outbox")
public record OutboxProperties(
        String schema,
        Duration pollInterval,
        int batchSize,
        Duration retention,
        int topicPartitions,
        int topicReplicas) {

    public OutboxProperties {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("maestro.outbox.schema must be set");
        }
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        retention = retention == null ? Duration.ofDays(7) : retention;
        topicPartitions = topicPartitions <= 0 ? 6 : topicPartitions;
        topicReplicas = topicReplicas <= 0 ? 1 : topicReplicas;
    }
}
