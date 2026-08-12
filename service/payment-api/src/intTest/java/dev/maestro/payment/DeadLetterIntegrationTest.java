package dev.maestro.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.maestro.events.Topics;
import dev.maestro.testing.MaestroInfrastructure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves a poison message ends on the dead-letter topic instead of being skipped.
 *
 * <p>Skipping is Spring Kafka's default after retries are exhausted, and for a payments
 * platform it is the worst available behaviour: a dropped command is a payment stuck
 * mid-state forever, with nothing left to say why. This test feeds the listener a
 * record that can never deserialise and asserts the platform's chosen ending — the
 * record parked on the DLQ, stamped with where it came from, waiting for redrive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeadLetterIntegrationTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MaestroInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", () -> "maestro_payment");
        registry.add("spring.datasource.password", () -> "maestro_payment");
        registry.add("spring.kafka.bootstrap-servers", MaestroInfrastructure::kafkaBootstrapServers);
    }

    @Test
    @DisplayName("a message that cannot be deserialised is dead-lettered, not skipped")
    void poisonMessageIsDeadLettered() {
        String poison = "this is not an event envelope {{{";

        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(
                    Topics.PAYMENT_EVENTS, "pay_poison_1", poison));
            producer.flush();
        }

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter("pay_poison_1");

        assertThat(deadLetter.value())
                .as("the record must arrive intact, or the post-mortem has no body to examine")
                .isEqualTo(poison);
        var originHeader = deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        assertThat(originHeader).as("the dead letter must say where it died").isNotNull();
        assertThat(new String(originHeader.value(), StandardCharsets.UTF_8))
                .isEqualTo(Topics.PAYMENT_EVENTS);
    }

    /** Polls the DLQ until the poison arrives; retries and backoff make this take seconds. */
    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MaestroInfrastructure.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dead-letter-test-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.PAYMENT_EVENTS_DLQ));
            for (int attempt = 0; attempt < 60; attempt++) {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("The poison message never reached the dead-letter topic");
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        MaestroInfrastructure.kafkaBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }
}
