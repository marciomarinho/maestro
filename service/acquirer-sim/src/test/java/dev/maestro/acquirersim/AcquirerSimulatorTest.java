package dev.maestro.acquirersim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.maestro.acquirersim.api.AcquirerResponse;
import dev.maestro.acquirersim.api.AuthorizeRequest;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The simulator's contract with the platform's retry logic.
 *
 * <p>Every test here defends a property the routing tests in {@code router} rely on. If
 * the simulator quietly stopped honouring idempotency keys, or started remembering
 * failures, those tests would still pass while proving something else.
 */
class AcquirerSimulatorTest {

    private static final String ACQUIRER = "northbank";

    private AcquirerSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new AcquirerSimulator(new AcquirerSimProperties(List.of(
                new AcquirerSimProperties.Acquirer(
                        ACQUIRER, "Northbank Acquiring", Duration.ofMillis(1), null))));
    }

    @Test
    void repeatingAnIdempotencyKeyReturnsTheOriginalApproval() {
        AcquirerResponse first = authorize("key-1");
        AcquirerResponse second = authorize("key-1");

        assertThat(first.outcome()).isEqualTo("APPROVED");
        assertThat(second.acquirerReference()).isEqualTo(first.acquirerReference());
        assertThat(second.authorizationCode()).isEqualTo(first.authorizationCode());
    }

    @Test
    void aDeclineIsRememberedBecauseTheIssuerDecidedIt() {
        simulator.degrade(ACQUIRER, alwaysDeclining());

        AcquirerResponse declined = authorize("key-1");
        assertThat(declined.outcome()).isEqualTo("DECLINED_BUSINESS");

        // Healing must not change the answer: the issuer already refused this
        // transaction, and a repeat of the same request is the same transaction.
        simulator.heal(ACQUIRER);
        assertThat(authorize("key-1").outcome()).isEqualTo("DECLINED_BUSINESS");
    }

    @Test
    void aTechnicalFailureIsNotRememberedSoTheRetryCanSucceed() {
        simulator.degrade(ACQUIRER, alwaysFailingTechnically());
        assertThat(authorize("key-1").outcome()).isEqualTo("DECLINED_TECHNICAL");

        // The router is required to re-present a failed authorization with the *same*
        // key. Remembering the failure would make that retry permanently unrecoverable,
        // and every failover in the platform would be untestable.
        simulator.heal(ACQUIRER);
        assertThat(authorize("key-1").outcome()).isEqualTo("APPROVED");
    }

    @Test
    void capacityIsRefusedAtTheDoorWithoutDecidingAnything() {
        simulator.degrade(ACQUIRER, new Behaviour(
                Duration.ofMillis(150), Duration.ZERO, 0, 0, 0, 1));

        // One slow call occupies the single permit while a second arrives.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> authorize("slow"));
            AcquirerResponse refused = awaitRefusal();
            assertThat(refused.outcome()).isEqualTo("THROTTLED");
            assertThat(refused.retryAfterMillis()).isNotNull();
        }

        // Nothing was decided, so the key is still free to succeed.
        simulator.heal(ACQUIRER);
        assertThat(authorize("refused").outcome()).isEqualTo("APPROVED");
    }

    @Test
    void concurrentDuplicatesAuthorizeExactlyOnce() throws InterruptedException {
        int callers = 16;
        CountDownLatch startTogether = new CountDownLatch(1);
        Set<String> references = ConcurrentHashMap.newKeySet();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, callers).forEach(i -> pool.submit(() -> {
                startTogether.await();
                references.add(authorize("the-same-key").acquirerReference());
                return null;
            }));
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(references)
                .as("sixteen simultaneous copies of one request are still one authorization")
                .hasSize(1);
    }

    @Test
    void anUnknownAcquirerIsRejectedRatherThanQuietlySimulated() {
        assertThatThrownBy(() -> authorize("elsewhere", "key-1"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("elsewhere");
    }

    @Test
    void healingRestoresTheConfiguredBehaviour() {
        simulator.degrade(ACQUIRER, alwaysFailingTechnically());
        assertThat(simulator.behaviourOf(ACQUIRER).isHealthy()).isFalse();

        assertThat(simulator.heal(ACQUIRER).isHealthy()).isTrue();
        assertThat(simulator.behaviourOf(ACQUIRER).latency()).isEqualTo(Duration.ofMillis(1));
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Retries until the concurrent slow call has taken the permit, so the test asserts
     * on capacity rather than on thread scheduling.
     */
    private AcquirerResponse awaitRefusal() {
        for (int i = 0; i < 200; i++) {
            AcquirerResponse response = authorize("refused-" + i);
            if ("THROTTLED".equals(response.outcome())) {
                return response;
            }
        }
        throw new AssertionError("The capacity cap never refused a request");
    }

    private AcquirerResponse authorize(String idempotencyKey) {
        return authorize(ACQUIRER, idempotencyKey);
    }

    private AcquirerResponse authorize(String acquirerId, String idempotencyKey) {
        return simulator.authorize(acquirerId, idempotencyKey, new AuthorizeRequest(
                "pay_1", "mch_demo", 1999L, "AUD", "tok_visa", "VISA"));
    }

    private static Behaviour alwaysDeclining() {
        return new Behaviour(Duration.ofMillis(1), Duration.ZERO, 1.0, 0, 0, 0);
    }

    private static Behaviour alwaysFailingTechnically() {
        return new Behaviour(Duration.ofMillis(1), Duration.ZERO, 0, 1.0, 0, 0);
    }
}
