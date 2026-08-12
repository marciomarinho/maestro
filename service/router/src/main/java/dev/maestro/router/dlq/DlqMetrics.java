package dev.maestro.router.dlq;

import dev.maestro.observability.MetricNames;
import dev.maestro.observability.MetricTags;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the depth of each dead-letter topic: what has died and not yet been
 * redriven.
 *
 * <p>Depth is end offset minus the redrive group's committed offset, because a dead
 * letter is not "handled" when it is written — it is handled when an operator has
 * redriven it. A rising value is the runbook's signal; a value stuck above zero is an
 * operator who fixed the cause and forgot the second half.
 *
 * <p>Lives in the router only to live somewhere: the topics are platform-wide, and one
 * watcher is enough.
 */
@Component
public class DlqMetrics {

    private static final Logger log = LoggerFactory.getLogger(DlqMetrics.class);

    private final KafkaAdmin kafkaAdmin;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> depths = new ConcurrentHashMap<>();
    private Admin admin;

    public DlqMetrics(KafkaAdmin kafkaAdmin, MeterRegistry meters) {
        this.kafkaAdmin = kafkaAdmin;
        this.meters = meters;
        DlqRedriveService.DLQ_TOPICS.forEach(topic -> {
            AtomicLong depth = new AtomicLong();
            depths.put(topic, depth);
            Gauge.builder(MetricNames.DLQ_DEPTH, depth, AtomicLong::get)
                    .description("Dead letters awaiting redrive")
                    .tag(MetricTags.TOPIC, topic)
                    .register(meters);
        });
    }

    @Scheduled(fixedDelayString = "30s", initialDelayString = "30s")
    public void sample() {
        try {
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            DlqRedriveService.DLQ_TOPICS.forEach(
                    topic -> latest.put(new TopicPartition(topic, 0), OffsetSpec.latest()));

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    client().listOffsets(latest).all().get();
            Map<TopicPartition, OffsetAndMetadata> committed = client()
                    .listConsumerGroupOffsets(DlqRedriveService.REDRIVE_GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get();

            ends.forEach((partition, end) -> {
                OffsetAndMetadata redriven = committed.get(partition);
                long consumed = redriven == null ? 0 : redriven.offset();
                depths.get(partition.topic()).set(Math.max(0, end.offset() - consumed));
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The broker being briefly unreachable should dent no service's health;
            // the gauges simply hold their last reading until the next pass.
            log.debug("DLQ depth sampling skipped: {}", e.getMessage());
        }
    }

    private synchronized Admin client() {
        if (admin == null) {
            admin = Admin.create(kafkaAdmin.getConfigurationProperties());
        }
        return admin;
    }
}
