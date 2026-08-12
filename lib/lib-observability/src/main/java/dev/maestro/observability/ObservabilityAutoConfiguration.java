package dev.maestro.observability;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * The observability defaults every service gets by existing.
 *
 * <p>Kept deliberately small: naming lives in {@link MetricNames}, correlation in
 * {@link LogContext}, and everything else — the Prometheus endpoint, the tracing
 * bridge, HTTP and Kafka instrumentation — is Boot auto-configuration that this module
 * merely puts on the classpath.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /**
     * Stamps every meter with the service's name. Prometheus adds {@code job} and
     * {@code instance} at scrape time, but those describe the scrape target; the
     * {@code application} tag travels with the metric no matter which path it took.
     */
    @Bean
    public MeterFilter applicationCommonTags(Environment environment) {
        String application = environment.getProperty("spring.application.name", "unknown");
        return MeterFilter.commonTags(Tags.of("application", application));
    }

    /**
     * Keeps W3C propagation alive when span export is switched off.
     *
     * <p>Boot 4 gates its {@code TextMapPropagator} behind
     * {@code @ConditionalOnEnabledTracingExport}: disable export and the noop
     * propagator silently takes over, so services stop writing {@code traceparent}
     * to anything — HTTP calls, and through the outbox, Kafka records. But
     * propagation and export are different concerns here: the everyday compose loop
     * runs without a trace backend, and the outbox rows written during it must still
     * carry real contexts. This bean fills exactly the export-disabled case; when
     * export is on, Boot's own propagator (which also carries baggage) is used.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "management.tracing.export.enabled", havingValue = false)
    public TextMapPropagator w3cPropagationDespiteDisabledExport() {
        return W3CTraceContextPropagator.getInstance();
    }

    /**
     * Keeps the platform's own heartbeat out of its telemetry.
     *
     * <p>Left unfiltered, the busiest traces in Tempo are the outbox relay polling
     * twice a second and the scrape hitting {@code /actuator/prometheus} every five —
     * thousands of identical one-span traces burying the payments they exist to
     * observe. Dropping the observation drops its metric too, which is also right:
     * "requests per second" should mean merchant traffic, not the monitoring watching
     * for it. The relay's health is a gauge ({@code maestro.outbox.*}), not a span.
     */
    @Bean
    public ObservationPredicate observabilityIsNotObserved() {
        return (name, context) -> {
            if ("tasks.scheduled.execution".equals(name)) {
                return false;
            }
            if (context instanceof ServerRequestObservationContext http) {
                return !http.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
