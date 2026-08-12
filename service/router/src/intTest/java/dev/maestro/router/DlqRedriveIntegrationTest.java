package dev.maestro.router;

import static org.assertj.core.api.Assertions.assertThat;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventEnvelope;
import dev.maestro.events.Topics;
import dev.maestro.testing.MaestroInfrastructure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives a dead letter through {@code POST /ops/dlq/redrive} and back onto its topic.
 *
 * <p>The redrive endpoint is the second half of every dead-letter incident — the
 * runbook's closing step — so it is tested the way an operator uses it: over HTTP,
 * with the ops token, against a real broker. The seeded record deliberately carries an
 * event type nothing handles, so its second life on the commands topic is a quiet one
 * rather than a loop back onto the queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DlqRedriveIntegrationTest {

    private static final String OPS_TOKEN = "ops_local_token";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private final EventCodec codec = new EventCodec();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MaestroInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", () -> "maestro_routing");
        registry.add("spring.datasource.password", () -> "maestro_routing");
        registry.add("spring.kafka.bootstrap-servers", MaestroInfrastructure::kafkaBootstrapServers);
    }

    @Test
    @DisplayName("a dead letter is returned to its original topic, once")
    void redriveReturnsDeadLettersToTheirTopic() throws Exception {
        String paymentId = "pay_redrive_" + System.nanoTime();
        String body = codec.serialize(
                EventEnvelope.of("payment.redrive_test", "mch_demo", paymentId,
                        Map.of("note", "no handler consumes this type")));

        try (KafkaProducer<String, String> producer = producer()) {
            ProducerRecord<String, String> deadLetter =
                    new ProducerRecord<>(Topics.PAYMENT_COMMANDS_DLQ, paymentId, body);
            deadLetter.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC,
                    Topics.PAYMENT_COMMANDS.getBytes(StandardCharsets.UTF_8));
            producer.send(deadLetter);
            producer.flush();
        }

        JsonNode first = redrive();
        assertThat(first.get("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(first.get("by_topic").get(Topics.PAYMENT_COMMANDS_DLQ).asInt())
                .isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> redriven = awaitOnCommandsTopic(paymentId);
        assertThat(redriven.value()).isEqualTo(body);
        assertThat(redriven.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC))
                .as("dead-letter bookkeeping must not follow the record back")
                .isNull();

        // Progress was committed: nothing left to redrive.
        assertThat(redrive().get("total").asInt()).isZero();
    }

    private JsonNode redrive() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/ops/dlq/redrive"))
                        .header("Authorization", "Bearer " + OPS_TOKEN)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private ConsumerRecord<String, String> awaitOnCommandsTopic(String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MaestroInfrastructure.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "redrive-test-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.PAYMENT_COMMANDS));
            for (int attempt = 0; attempt < 30; attempt++) {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("The redriven record never reappeared on the commands topic");
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        MaestroInfrastructure.kafkaBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }
}
