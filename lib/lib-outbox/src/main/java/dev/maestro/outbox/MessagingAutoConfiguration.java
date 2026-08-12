package dev.maestro.outbox;

import dev.maestro.events.EventCodec;
import dev.maestro.events.Topics;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * What every service on the event bus gets: the codec, the topics, a producer for the
 * platform's wire format, and dead-letter handling for its listeners.
 *
 * <p>Split from {@link OutboxAutoConfiguration} because consuming and owning an outbox
 * are different capabilities — the ledger consumes events but publishes nothing, and it
 * still needs its poison messages dead-lettered rather than skipped.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventCodec eventCodec() {
        return new EventCodec();
    }

    /**
     * Declares the platform's topics rather than letting the broker invent them.
     *
     * <p>Auto-created topics get the broker's default partition count, which is one. That
     * quietly repeals ADR-0005: partitioning by payment exists so that unrelated payments
     * are processed independently, and with a single partition every payment in the
     * platform queues behind every other one. It costs nothing while acquirers answer in
     * forty milliseconds, and it is why a brownout — where one unresolved authorization
     * holds its thread for seconds — stalls traffic that has nothing to do with the
     * acquirer that is unwell.
     *
     * <p>{@code KafkaAdmin} applies these at startup and will raise the partition count
     * of an existing topic, so an environment created before this existed is corrected
     * rather than left behind.
     */
    @Bean
    public NewTopic paymentCommandsTopic(MessagingProperties properties) {
        return topic(Topics.PAYMENT_COMMANDS, properties);
    }

    @Bean
    public NewTopic paymentEventsTopic(MessagingProperties properties) {
        return topic(Topics.PAYMENT_EVENTS, properties);
    }

    /**
     * The dead-letter topics, deliberately single-partition.
     *
     * <p>Nothing consumes them automatically — they exist so an operator can look at what
     * failed and redrive it — and a single partition keeps that inspection in one place
     * and in order.
     */
    @Bean
    public NewTopic paymentCommandsDlqTopic(MessagingProperties properties) {
        return dlqTopic(Topics.PAYMENT_COMMANDS_DLQ, properties);
    }

    @Bean
    public NewTopic paymentEventsDlqTopic(MessagingProperties properties) {
        return dlqTopic(Topics.PAYMENT_EVENTS_DLQ, properties);
    }

    /**
     * A producer owned by the platform rather than borrowed from the application.
     *
     * <p>Everything published — outbox envelopes, dead letters, redriven records — is a
     * string keyed by a string, so this template fixes its own serializers instead of
     * inheriting whatever the surrounding application configured: the event wire format
     * is a contract between services and should not change because someone adjusted an
     * unrelated producer setting. Broker addresses and the rest still come from
     * {@code spring.kafka}.
     */
    @Bean
    public KafkaTemplate<String, String> eventKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties();
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * Retry briefly, then dead-letter; never skip.
     *
     * <p>Boot's default error handler retries and then <em>logs and moves on</em>, which
     * for a payments platform is the worst available option — a dropped command is a
     * payment stuck in {@code AUTHORIZING} forever. Three retries with backoff absorb a
     * transient (a database blip, a lock timeout); anything that survives them is poison
     * or an outage, and both belong on the dead-letter topic where they block nothing,
     * lose nothing, and wait for {@code POST /ops/dlq/redrive} once the cause is fixed.
     *
     * <p>Partition of the original record is not preserved: the DLQ has one partition,
     * so dead letters read back in the order they died.
     */
    @Bean
    @ConditionalOnMissingBean
    public CommonErrorHandler deadLetterErrorHandler(KafkaTemplate<String, String> eventKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                eventKafkaTemplate,
                (record, exception) -> new TopicPartition(Topics.dlqFor(record.topic()), 0));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    private static NewTopic topic(String name, MessagingProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }

    private static NewTopic dlqTopic(String name, MessagingProperties properties) {
        return TopicBuilder.name(name)
                .partitions(1)
                .replicas(properties.topicReplicas())
                .build();
    }
}
