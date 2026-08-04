package dev.maestro.outbox;

import dev.maestro.events.EventCodec;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the outbox for any service that owns one.
 *
 * <p>A service opts in by setting {@code maestro.outbox.schema}; the outbox table
 * always lives in the schema whose transactions it joins.
 *
 * <p>Ordered after the data source, JDBC and Kafka auto-configurations, since it builds
 * on all three. Without the ordering it is evaluated first and finds none of them.
 */
@AutoConfiguration(after = {
        DataSourceAutoConfiguration.class,
        JdbcClientAutoConfiguration.class,
        KafkaAutoConfiguration.class
})
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventCodec eventCodec() {
        return new EventCodec();
    }

    @Bean
    public TransactionTemplate outboxTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    /**
     * A producer owned by the outbox rather than borrowed from the application.
     *
     * <p>The relay always publishes a serialised envelope as a string, so it fixes its
     * own serializers instead of inheriting whatever the surrounding application
     * configured — the event wire format is a contract between services and should not
     * change because someone adjusted an unrelated producer setting. Broker addresses
     * and the rest still come from {@code spring.kafka}.
     */
    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties();
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * A small virtual-thread executor for after-commit relay wake-ups. These tasks are
     * short and blocking, which is exactly what virtual threads are for (ADR-0002).
     */
    @Bean(destroyMethod = "close")
    public OutboxWakeUpExecutor outboxWakeUpExecutor() {
        return new OutboxWakeUpExecutor();
    }

    @Bean
    public OutboxRelay outboxRelay(
            JdbcClient jdbc,
            KafkaTemplate<String, String> outboxKafkaTemplate,
            OutboxProperties properties,
            TransactionTemplate outboxTransactionTemplate,
            OutboxWakeUpExecutor executor) {
        return new OutboxRelay(
                jdbc, outboxKafkaTemplate, properties, outboxTransactionTemplate, executor);
    }

    @Bean
    public OutboxWriter outboxWriter(
            JdbcClient jdbc,
            EventCodec eventCodec,
            OutboxProperties properties,
            OutboxRelay relay) {
        return new OutboxWriter(jdbc, eventCodec, properties, relay::wakeUp);
    }

    /** Named type so the executor can be injected unambiguously and closed on shutdown. */
    public static final class OutboxWakeUpExecutor implements Executor, AutoCloseable {

        private final java.util.concurrent.ExecutorService delegate =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("outbox-wake-", 0).factory());

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void close() {
            delegate.shutdown();
        }
    }
}
