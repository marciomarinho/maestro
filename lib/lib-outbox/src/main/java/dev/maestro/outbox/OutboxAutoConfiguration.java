package dev.maestro.outbox;

import dev.maestro.events.EventCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the outbox for any service that owns one.
 *
 * <p>A service opts in by setting {@code maestro.outbox.schema}; the outbox table
 * always lives in the schema whose transactions it joins. A service without the
 * property — the ledger consumes but never publishes — gets only the shared messaging
 * wiring from {@link MessagingAutoConfiguration}.
 *
 * <p>Ordered after the data source, JDBC and Kafka auto-configurations, since it builds
 * on all three. Without the ordering it is evaluated first and finds none of them.
 */
@AutoConfiguration(after = {
        DataSourceAutoConfiguration.class,
        JdbcClientAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        MessagingAutoConfiguration.class
})
@ConditionalOnProperty("maestro.outbox.schema")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    public TransactionTemplate outboxTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
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
            KafkaTemplate<String, String> eventKafkaTemplate,
            OutboxProperties properties,
            TransactionTemplate outboxTransactionTemplate,
            OutboxWakeUpExecutor executor) {
        return new OutboxRelay(
                jdbc, eventKafkaTemplate, properties, outboxTransactionTemplate, executor);
    }

    @Bean
    public OutboxWriter outboxWriter(
            JdbcClient jdbc,
            EventCodec eventCodec,
            OutboxProperties properties,
            OutboxRelay relay,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator) {
        return new OutboxWriter(
                jdbc, eventCodec, properties, relay::wakeUp,
                currentTraceParent(tracer, propagator));
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public OutboxMetrics outboxMetrics(
            JdbcClient jdbc, OutboxProperties properties, MeterRegistry meters) {
        return new OutboxMetrics(jdbc, properties, meters);
    }

    /**
     * Reads the calling thread's trace context as a W3C {@code traceparent} string, or
     * {@code null} when there is no tracer or no active span. Resolved through
     * {@link ObjectProvider} so the outbox works unchanged in a service — or a test
     * slice — that has no tracing configured at all.
     */
    private static Supplier<String> currentTraceParent(
            ObjectProvider<Tracer> tracerProvider, ObjectProvider<Propagator> propagatorProvider) {
        return () -> {
            Tracer tracer = tracerProvider.getIfAvailable();
            Propagator propagator = propagatorProvider.getIfAvailable();
            if (tracer == null || propagator == null) {
                return null;
            }
            TraceContext context = tracer.currentTraceContext().context();
            if (context == null) {
                return null;
            }
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(context, carrier, Map::put);
            return carrier.get("traceparent");
        };
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
