package dev.maestro.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.maestro.events.EventCodec;
import dev.maestro.events.EventTypes;
import dev.maestro.events.Topics;
import dev.maestro.events.payload.AuthorizationRequested;
import dev.maestro.testing.MaestroInfrastructure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives payment-api against real PostgreSQL and real Kafka.
 *
 * <p>These tests exist to check what a mock cannot: that the idempotency constraint
 * actually suppresses a duplicate under a genuine concurrent race, that the state change
 * and the outbox row really do commit together, and that the published event really is
 * readable off the broker by an independent consumer.
 *
 * <p>Requests go out over a plain {@link HttpClient} rather than a test-framework client,
 * so what is exercised is the HTTP surface a merchant would actually call — headers,
 * status codes and all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentLifecycleIntegrationTest {

    private static final String API_KEY = "sk_test_maestro_demo_0001";
    private static final String OTHER_MERCHANT_KEY = "sk_test_other_merchant";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    private final EventCodec codec = new EventCodec();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MaestroInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", () -> "maestro_payment");
        registry.add("spring.datasource.password", () -> "maestro_payment");
        registry.add("spring.kafka.bootstrap-servers", MaestroInfrastructure::kafkaBootstrapServers);
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE payment.refund, payment.outbox_event, payment.idempotency_record, "
                        + "payment.payment CASCADE")
                .update();
    }

    @Test
    @DisplayName("creating and confirming a payment writes the state change and the command atomically")
    void createAndConfirmWritesStateAndOutboxTogether() {
        HttpResponse<String> response = createPayment("atomic-1", 1999L, API_KEY);

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode body = json(response);
        String paymentId = body.get("id").asString();
        assertThat(paymentId).startsWith("pay_");
        assertThat(body.get("status").asString()).isEqualTo("AUTHORIZING");

        assertThat(countPayments()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_type FROM payment.outbox_event WHERE aggregate_id = :id")
                        .param("id", paymentId)
                        .query(String.class)
                        .list())
                .containsExactly(EventTypes.AUTHORIZATION_REQUESTED);
    }

    @Test
    @DisplayName("a replayed request returns the original response and creates nothing new")
    void replayReturnsTheOriginalResponse() {
        HttpResponse<String> first = createPayment("replay-1", 1999L, API_KEY);
        HttpResponse<String> second = createPayment("replay-1", 1999L, API_KEY);

        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(json(second).get("id")).isEqualTo(json(first).get("id"));
        assertThat(second.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is rejected, not silently replayed")
    void reusingAKeyWithADifferentBodyConflicts() {
        createPayment("reuse-1", 1999L, API_KEY);

        HttpResponse<String> conflicting = createPayment("reuse-1", 9999L, API_KEY);

        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(json(conflicting).get("code").asString()).isEqualTo("idempotency_key_reuse");
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent identical requests create exactly one payment")
    void concurrentRequestsWithTheSameKeyCreateOnePayment() throws Exception {
        // The point of the whole idempotency design. A merchant's HTTP client retrying an
        // ambiguous timeout produces exactly this race, and getting it wrong charges a
        // customer twice. No mock can demonstrate it — only a real unique constraint
        // under real concurrency can.
        int concurrency = 12;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<HttpResponse<String>>> calls = IntStream.range(0, concurrency)
                    .<Callable<HttpResponse<String>>>mapToObj(
                            i -> () -> createPayment("race-1", 1999L, API_KEY))
                    .toList();

            List<Integer> statuses = pool.invokeAll(calls).stream()
                    .map(PaymentLifecycleIntegrationTest::get)
                    .map(HttpResponse::statusCode)
                    .toList();

            // Losers of the race either replay the winner's response or are told the
            // original is still in flight. Either is correct; a second payment is not.
            assertThat(statuses).allMatch(status -> status == 202 || status == 409);
            assertThat(countPayments()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the relay publishes the command to Kafka, readable by an independent consumer")
    void outboxRelayPublishesToKafka() {
        String paymentId = json(createPayment("publish-1", 2500L, API_KEY)).get("id").asString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(unpublishedOutboxRows()).isZero());

        AuthorizationRequested published = consumeCommandFor(paymentId);
        assertThat(published.amountMinor()).isEqualTo(2500L);
        assertThat(published.currency()).isEqualTo("AUD");
        assertThat(published.cardToken()).isEqualTo("tok_visa_4242");
    }

    @Test
    @DisplayName("the trace context rides the outbox row onto the Kafka record header")
    void traceContextRidesTheOutboxRow() {
        // The merchant's caller is upstream of Maestro: whatever trace they started must
        // still be the trace on the command the router consumes, or the asynchronous hop
        // splits every payment into disconnected fragments. The path under test is the
        // whole relay mechanism: HTTP header → request span → outbox row → traceparent
        // Kafka header (ADR-0018).
        String upstreamTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Idempotency-Key", "trace-1")
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + upstreamTraceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "amount_minor": 1999,
                          "currency": "AUD",
                          "card_token": "tok_visa_4242",
                          "reference": "order-trace",
                          "confirm": true
                        }
                        """))
                .build());
        String paymentId = json(response).get("id").asString();

        String storedTraceParent = Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql(
                                "SELECT trace_parent FROM payment.outbox_event WHERE aggregate_id = :id")
                        .param("id", paymentId)
                        .query(String.class)
                        .optional()
                        .orElse(null), value -> value != null);
        assertThat(storedTraceParent)
                .as("the outbox row must carry the request's trace context")
                .contains(upstreamTraceId);

        var record = consumeCommandRecordFor(paymentId);
        var header = record.headers().lastHeader("traceparent");
        assertThat(header).as("the relay must restore the context as a Kafka header").isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).contains(upstreamTraceId);
    }

    @Test
    @DisplayName("a request without a credential is rejected before it reaches any merchant data")
    void unauthenticatedRequestsAreRejected() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments/pay_whatever"))
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("another merchant's payment is a 404, never a 403")
    void anotherMerchantsPaymentIsNotFound() {
        // 403 would confirm the identifier exists, which turns the API into an
        // enumeration oracle for another tenant's payment volume.
        String paymentId = json(createPayment("tenant-1", 1999L, API_KEY)).get("id").asString();
        seedOtherMerchant();

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments/" + paymentId))
                .header("Authorization", "Bearer " + OTHER_MERCHANT_KEY)
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("concurrent captures of one payment produce exactly one capture")
    void concurrentCapturesProduceOneCapture() throws Exception {
        // Every request carries a *different* idempotency key, so this is not the
        // idempotency mechanism being retested — it is the guarded state transition. Two
        // genuinely separate requests racing must still capture once, or the merchant is
        // charged twice for one order.
        String paymentId = json(createPayment("capture-race", 5000L, API_KEY)).get("id").asString();
        markAuthorized(paymentId);

        int concurrency = 10;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Integer> statuses = pool.invokeAll(IntStream.range(0, concurrency)
                            .<Callable<HttpResponse<String>>>mapToObj(
                                    i -> () -> capture(paymentId, "capture-race-" + i))
                            .toList())
                    .stream()
                    .map(PaymentLifecycleIntegrationTest::get)
                    .map(HttpResponse::statusCode)
                    .toList();

            assertThat(statuses).filteredOn(status -> status == 202).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(concurrency - 1);
        }

        assertThat(outboxCount(paymentId, EventTypes.CAPTURE_REQUESTED))
                .as("exactly one capture instruction may reach the router")
                .isEqualTo(1);
        assertThat(statusOf(paymentId)).isEqualTo("CAPTURING");
    }

    @Test
    @DisplayName("concurrent refunds can never exceed what was captured")
    void concurrentRefundsCannotExceedCaptured() throws Exception {
        String paymentId = json(createPayment("refund-race", 1000L, API_KEY)).get("id").asString();
        markCaptured(paymentId, 1000L);

        // Ten simultaneous refunds of 200 against 1000 captured. Five can succeed; the
        // rest must be refused. A read-then-write check would let all ten through.
        int concurrency = 10;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Integer> statuses = pool.invokeAll(IntStream.range(0, concurrency)
                            .<Callable<HttpResponse<String>>>mapToObj(
                                    i -> () -> refund(paymentId, "refund-race-" + i, 200L))
                            .toList())
                    .stream()
                    .map(PaymentLifecycleIntegrationTest::get)
                    .map(HttpResponse::statusCode)
                    .toList();

            assertThat(statuses).filteredOn(status -> status == 202).hasSize(5);
            assertThat(statuses).filteredOn(status -> status == 422).hasSize(5);
        }

        assertThat(reservedMinor(paymentId))
                .as("reserved refunds may never exceed the captured amount")
                .isEqualTo(1000L);
    }

    @Test
    @DisplayName("a capture larger than the authorization is refused")
    void captureCannotExceedTheAuthorization() {
        String paymentId = json(createPayment("over-capture", 1000L, API_KEY)).get("id").asString();
        markAuthorized(paymentId);

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments/" + paymentId + "/capture"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Idempotency-Key", "over-capture-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount_minor\": 2000}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("capture_exceeds_authorized");
    }

    // --- helpers -----------------------------------------------------------

    private HttpResponse<String> capture(String paymentId, String idempotencyKey) {
        return send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments/" + paymentId + "/capture"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
    }

    private HttpResponse<String> refund(String paymentId, String idempotencyKey, long amountMinor) {
        return send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments/" + paymentId + "/refunds"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"amount_minor\": " + amountMinor + "}"))
                .build());
    }

    /** Puts a payment where the router would have, without needing the router running. */
    private void markAuthorized(String paymentId) {
        jdbc.sql("""
                UPDATE payment.payment
                   SET status = 'AUTHORIZED', acquirer_id = 'northbank',
                       acquirer_reference = 'northbank_test', authorized_at = now(),
                       authorization_expires_at = now() + INTERVAL '7 days'
                 WHERE id = :id
                """).param("id", paymentId).update();
    }

    private void markCaptured(String paymentId, long capturedMinor) {
        jdbc.sql("""
                UPDATE payment.payment
                   SET status = 'CAPTURED', captured_amount_minor = :captured,
                       acquirer_id = 'northbank', acquirer_reference = 'northbank_test'
                 WHERE id = :id
                """)
                .param("id", paymentId)
                .param("captured", capturedMinor)
                .update();
    }

    private String statusOf(String paymentId) {
        return jdbc.sql("SELECT status FROM payment.payment WHERE id = :id")
                .param("id", paymentId)
                .query(String.class)
                .single();
    }

    private long reservedMinor(String paymentId) {
        return jdbc.sql("SELECT refund_reserved_minor FROM payment.payment WHERE id = :id")
                .param("id", paymentId)
                .query(Long.class)
                .single();
    }

    private long outboxCount(String paymentId, String eventType) {
        return jdbc.sql("""
                SELECT count(*) FROM payment.outbox_event
                 WHERE aggregate_id = :id AND event_type = :eventType
                """)
                .param("id", paymentId)
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }


    private HttpResponse<String> createPayment(String idempotencyKey, long amountMinor, String apiKey) {
        String body = """
                {
                  "amount_minor": %d,
                  "currency": "AUD",
                  "card_token": "tok_visa_4242",
                  "reference": "order-1",
                  "confirm": true
                }
                """.formatted(amountMinor);
        return send(HttpRequest.newBuilder()
                .uri(uri("/v1/payments"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Request failed: " + request.uri(), e);
        }
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private long countPayments() {
        return jdbc.sql("SELECT count(*) FROM payment.payment").query(Long.class).single();
    }

    private long unpublishedOutboxRows() {
        return jdbc.sql("SELECT count(*) FROM payment.outbox_event WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    private void seedOtherMerchant() {
        jdbc.sql("""
                INSERT INTO payment.merchant (id, name, status, default_currency)
                VALUES ('mch_other', 'Other Merchant', 'ACTIVE', 'AUD')
                ON CONFLICT (id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO payment.api_key (id, merchant_id, key_prefix, key_hash, role)
                VALUES ('ak_other', 'mch_other', 'sk_test', :hash, 'merchant_admin')
                ON CONFLICT (key_hash) DO NOTHING
                """)
                .param("hash", sha256Hex(OTHER_MERCHANT_KEY))
                .update();
    }

    private AuthorizationRequested consumeCommandFor(String paymentId) {
        return codec.deserialize(consumeCommandRecordFor(paymentId).value(), AuthorizationRequested.class)
                .payload();
    }

    private ConsumerRecord<String, String> consumeCommandRecordFor(String paymentId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MaestroInfrastructure.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "integration-test-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.PAYMENT_COMMANDS));
            for (int attempt = 0; attempt < 30; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (var record : records) {
                    if (!EventTypes.AUTHORIZATION_REQUESTED.equals(codec.peekEventType(record.value()))) {
                        continue;
                    }
                    var envelope = codec.deserialize(record.value(), AuthorizationRequested.class);
                    if (paymentId.equals(envelope.payload().paymentId())) {
                        // Keyed by payment, which is what guarantees per-payment ordering.
                        assertThat(record.key()).isEqualTo(paymentId);
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No authorization command was published for " + paymentId);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
