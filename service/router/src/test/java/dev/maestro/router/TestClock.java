package dev.maestro.router;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test moves.
 *
 * <p>Every claim this phase makes is a claim about time: a brownout is detected within
 * seconds, a healed acquirer regains traffic within seconds, evidence loses half its
 * weight in thirty. Tested against the wall clock those become sleeps — slow, and flaky
 * on a loaded CI machine, which is the worst combination because the response to a flaky
 * timing test is to loosen the bound until it proves nothing.
 *
 * <p>Advancing time by hand instead makes the same assertions exact and instant. A test
 * that says "thirty seconds after the failures start, its share is below ten percent"
 * runs in a millisecond and means precisely what it says.
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public TestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** A fixed, arbitrary starting point. Tests care about elapsed time, not the date. */
    public static TestClock startingNow() {
        return new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
