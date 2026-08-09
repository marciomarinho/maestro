package dev.maestro.router.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.domain.acquirer.DeclineCode;
import dev.maestro.router.RouterProperties;
import dev.maestro.router.TestClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the router forms and changes its mind, tested against a clock the test controls.
 *
 * <p>These are the properties the flagship rests on. Every one of them is a way a routing
 * system is commonly wrong: reacting too slowly to be useful, reacting so fast that noise
 * evicts a healthy acquirer, blaming an acquirer for the issuer's decisions, or holding a
 * damning opinion of a corridor it stopped sending traffic to.
 */
class HealthRegistryTest {

    private static final CorridorKey NORTHBANK = new CorridorKey("northbank", "VISA:AUD");
    private static final Duration HALF_LIFE = Duration.ofSeconds(30);

    private TestClock clock;
    private HealthRegistry registry;

    @BeforeEach
    void setUp() {
        clock = TestClock.startingNow();
        registry = new HealthRegistry(
                new RouterProperties(
                        List.of(), null, RouterProperties.Health.defaults(),
                        null, null, null, null, null, "test-ops-token"),
                clock);
    }

    @Test
    void aFreshCorridorAnswersWithThePriorRatherThanWithNothing() {
        CorridorHealth.Reading reading = registry.readingFor(NORTHBANK);

        assertThat(reading.approvalRate()).isEqualTo(0.90);
        assertThat(reading.technicalFailureRate()).isEqualTo(0.02);
        assertThat(reading.latencyMillis()).isEmpty();
        assertThat(reading.samples()).isZero();
    }

    @Test
    void oneFailureDoesNotCondemnALowTrafficCorridor() {
        // The corridor that sees four requests an hour is exactly the one a naive
        // implementation evicts permanently on a single unlucky timeout.
        record(new AcquirerOutcome.TechnicalFailure("ISSUER_UNAVAILABLE", "down"), 40);

        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate())
                .as("one failure against a prior worth twenty observations")
                .isLessThan(0.10);
    }

    @Test
    void aSustainedBrownoutIsVisibleLongBeforeOneHalfLife() {
        healthyTrafficFor(Duration.ofMinutes(5));
        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate()).isLessThan(0.05);

        // Ten seconds — a third of the half-life — against five minutes of good history.
        failingTrafficFor(Duration.ofSeconds(10));
        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate())
                .as("detection is what has to be fast: ten seconds in, the reading has "
                        + "moved most of an order of magnitude off the prior, which is "
                        + "already enough for selection to act on")
                .isGreaterThan(0.15);

        failingTrafficFor(Duration.ofSeconds(20));
        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate())
                .as("by one half-life, half the evidence behind the old opinion is gone "
                        + "and the reading is most of the way to the truth")
                .isGreaterThan(0.45);
    }

    @Test
    void aDeclineIsTheIssuersDecisionAndNotTheAcquirersFault() {
        for (int i = 0; i < 200; i++) {
            record(new AcquirerOutcome.BusinessDecline(DeclineCode.INSUFFICIENT_FUNDS, "no funds"), 45);
            clock.advance(Duration.ofMillis(100));
        }

        CorridorHealth.Reading reading = registry.readingFor(NORTHBANK);
        assertThat(reading.approvalRate())
                .as("approval rate collapses, because it did")
                .isLessThan(0.15);
        assertThat(reading.technicalFailureRate())
                .as("availability is untouched — the acquirer answered every time")
                .isLessThan(0.05);
    }

    @Test
    void approvalRateIsMeasuredOverDecisiveOutcomesOnly() {
        for (int i = 0; i < 100; i++) {
            record(new AcquirerOutcome.Approved("ref", "000000"), 45);
            clock.advance(Duration.ofMillis(50));
        }
        for (int i = 0; i < 100; i++) {
            record(new AcquirerOutcome.Timeout(5_000), 5_000);
            clock.advance(Duration.ofMillis(50));
        }

        CorridorHealth.Reading reading = registry.readingFor(NORTHBANK);
        assertThat(reading.approvalRate())
                .as("a timeout is not a decline; of the times it answered, it always said yes")
                .isGreaterThan(0.90);
        assertThat(reading.technicalFailureRate())
                .as("but it stopped answering half the time, and that is an outage")
                .isGreaterThan(0.40);
    }

    @Test
    void evidenceDecaysEvenWhenNoTrafficArrivesToDecayIt() {
        failingTrafficFor(Duration.ofMinutes(2));
        double condemned = registry.readingFor(NORTHBANK).technicalFailureRate();
        assertThat(condemned).isGreaterThan(0.80);

        // Traffic stops entirely — the corridor was demoted, so it is receiving nothing.
        double samplesWhenCondemned = registry.readingFor(NORTHBANK).samples();
        clock.advance(HALF_LIFE.multipliedBy(6));

        CorridorHealth.Reading fading = registry.readingFor(NORTHBANK);
        assertThat(fading.samples())
                .as("confidence is what decays first, and it decays hard")
                .isLessThan(samplesWhenCondemned / 50);
        assertThat(fading.technicalFailureRate())
                .as("certainty that it is broken has weakened to suspicion, purely because "
                        + "the evidence got old — no traffic was needed to soften it")
                .isBetween(0.15, 0.35);

        // Long enough and it is forgotten entirely, which is the correct end state: an
        // acquirer nobody has spoken to in ten minutes is an unknown, not a pariah.
        clock.advance(HALF_LIFE.multipliedBy(10));
        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate())
                .isCloseTo(0.02, within(0.02));
    }

    @Test
    void throttlingCountsAgainstAvailabilityButNotAgainstLatency() {
        for (int i = 0; i < 50; i++) {
            record(new AcquirerOutcome.Approved("ref", "000000"), 400);
            clock.advance(Duration.ofMillis(20));
        }
        double honestLatency = registry.readingFor(NORTHBANK).latencyMillis().orElseThrow();

        for (int i = 0; i < 50; i++) {
            record(new AcquirerOutcome.Throttled(100), 1);
            clock.advance(Duration.ofMillis(20));
        }

        CorridorHealth.Reading reading = registry.readingFor(NORTHBANK);
        assertThat(reading.technicalFailureRate()).isGreaterThan(0.30);
        assertThat(reading.latencyMillis().orElseThrow())
                .as("a refusal at the door returns instantly; counting it would make a "
                        + "saturated acquirer look like the fastest on the panel")
                .isEqualTo(honestLatency);
    }

    @Test
    void aRestoredSnapshotIsAHintThatLiveTrafficCanOverturn() {
        registry.restore(NORTHBANK, 0.20, 0.75, 900, 5_000);

        assertThat(registry.readingFor(NORTHBANK).samples())
                .as("five thousand samples restored, capped at ten")
                .isEqualTo(10.0);

        // Half a minute of healthy traffic is enough to overrule it.
        healthyTrafficFor(HALF_LIFE);
        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate()).isLessThan(0.10);
    }

    @Test
    void healthIsPerCorridorNotPerAcquirer() {
        CorridorKey mastercard = new CorridorKey("northbank", "MASTERCARD:AUD");
        for (int i = 0; i < 100; i++) {
            registry.record(mastercard, new AcquirerOutcome.TechnicalFailure("x", "down"), 40);
            registry.record(NORTHBANK, new AcquirerOutcome.Approved("ref", "000000"), 40);
            clock.advance(Duration.ofMillis(100));
        }

        assertThat(registry.readingFor(mastercard).technicalFailureRate()).isGreaterThan(0.80);
        assertThat(registry.readingFor(NORTHBANK).technicalFailureRate())
                .as("the same bank, healthy on Visa and failing on Mastercard — which is "
                        + "the case a single figure per acquirer describes neither of")
                .isLessThan(0.05);
    }

    // --- helpers ------------------------------------------------------------

    private void healthyTrafficFor(Duration duration) {
        driveFor(duration, new AcquirerOutcome.Approved("ref", "000000"), 45);
    }

    private void failingTrafficFor(Duration duration) {
        driveFor(duration, new AcquirerOutcome.TechnicalFailure("ISSUER_UNAVAILABLE", "down"), 40);
    }

    /** Ten requests a second, which is what the brownout demo drives. */
    private void driveFor(Duration duration, AcquirerOutcome outcome, long latencyMillis) {
        for (long elapsed = 0; elapsed < duration.toMillis(); elapsed += 100) {
            record(outcome, latencyMillis);
            clock.advance(Duration.ofMillis(100));
        }
    }

    private void record(AcquirerOutcome outcome, long latencyMillis) {
        registry.record(NORTHBANK, outcome, latencyMillis);
    }
}
