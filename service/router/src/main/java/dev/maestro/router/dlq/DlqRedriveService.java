package dev.maestro.router.dlq;

import dev.maestro.events.Topics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

/**
 * Returns dead letters to the topics they died on.
 *
 * <p>Deliberately not automatic. A record reaches a dead-letter topic because retrying
 * did not help, so retrying harder on a schedule is just the same failure with a
 * heartbeat; the useful sequence is <em>fix the cause, then redrive</em>, and only an
 * operator knows when the first half has happened. This service is the second half.
 *
 * <p>Progress is committed per batch under the {@value #REDRIVE_GROUP} group, so a
 * crash mid-redrive resumes where it stopped and a record is never redriven twice by
 * the same offset. A record that fails again after redrive simply dead-letters again —
 * the loop is safe, it just goes through a human each time.
 */
@Service
public class DlqRedriveService {

    public static final String REDRIVE_GROUP = "dlq-redrive";
    public static final List<String> DLQ_TOPICS =
            List.of(Topics.PAYMENT_COMMANDS_DLQ, Topics.PAYMENT_EVENTS_DLQ);

    private static final String DLT_HEADER_PREFIX = "kafka_dlt-";

    private static final Logger log = LoggerFactory.getLogger(DlqRedriveService.class);

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, String> eventKafkaTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DlqRedriveService(
            KafkaProperties kafkaProperties, KafkaTemplate<String, String> eventKafkaTemplate) {
        this.kafkaProperties = kafkaProperties;
        this.eventKafkaTemplate = eventKafkaTemplate;
    }

    /** @return records redriven, per dead-letter topic */
    public Map<String, Integer> redrive() {
        if (!running.compareAndSet(false, true)) {
            throw new RedriveInProgressException();
        }
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerConfig(), new StringDeserializer(), new StringDeserializer())) {
            // Assigned, not subscribed: a redrive is a bounded pass over what is there
            // now, not a membership in a group that rebalances.
            consumer.assign(DLQ_TOPICS.stream().map(t -> new TopicPartition(t, 0)).toList());

            Map<String, Integer> redriven = new LinkedHashMap<>();
            DLQ_TOPICS.forEach(topic -> redriven.put(topic, 0));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    redriveOne(record);
                    redriven.merge(record.topic(), 1, Integer::sum);
                }
                // Committed after the records are back on their topics, so a crash here
                // re-redrives rather than loses — the same duplicates-never-losses
                // choice the relay makes, absorbed by the same consumer idempotency.
                consumer.commitSync();
            }
            log.info("DLQ redrive complete: {}", redriven);
            return redriven;
        } finally {
            running.set(false);
        }
    }

    private void redriveOne(ConsumerRecord<String, String> record) {
        String destination = originalTopicOf(record);
        ProducerRecord<String, String> out =
                new ProducerRecord<>(destination, record.key(), record.value());
        // The original headers ride along — the trace context in particular, so the
        // redriven record still belongs to the payment's trace. The dead-letter
        // bookkeeping headers do not; they describe the death, not the message.
        for (Header header : record.headers()) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX)) {
                out.headers().add(header);
            }
        }
        eventKafkaTemplate.send(out).join();
    }

    /**
     * The recoverer stamps the origin on every dead letter; the naming convention is
     * the fallback for a record that arrived some other way.
     */
    private static String originalTopicOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (header != null) {
            return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return record.topic().replace(".dlq.", ".");
    }

    private Map<String, Object> consumerConfig() {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        config.put(ConsumerConfig.GROUP_ID_CONFIG, REDRIVE_GROUP);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return config;
    }

    /** One redrive at a time; a second request during one is a caller error, not a queue. */
    public static final class RedriveInProgressException extends RuntimeException {
        RedriveInProgressException() {
            super("A dead-letter redrive is already running");
        }
    }
}
