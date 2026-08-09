package dev.maestro.router.health;

import dev.maestro.router.RouterProperties;
import java.util.OptionalDouble;

/**
 * What the router currently believes about one acquirer on one corridor.
 *
 * <p>Three signals, kept separate on purpose:
 *
 * <ul>
 *   <li><strong>Approval rate</strong> — approvals over <em>decisive</em> outcomes. A
 *       timeout is not a decline, and folding it in here would let an outage look like an
 *       issuer that has turned strict.</li>
 *   <li><strong>Technical failure rate</strong> — failures over <em>all</em> attempts.
 *       A different denominator to the one above, deliberately, because the questions are
 *       different: "of the times this acquirer answered, how often was it yes" and "how
 *       often does this acquirer answer at all".</li>
 *   <li><strong>Latency</strong> — how long an answer takes when one arrives.</li>
 * </ul>
 *
 * <p>Keeping them apart is what makes a brownout legible rather than merely visible. An
 * acquirer whose approval rate is intact and whose technical failure rate has spiked is
 * having an outage; one whose technical failure rate is flat and whose approval rate has
 * collapsed has had a risk rule changed, and no amount of failing over will help.
 */
public final class CorridorHealth {

    private final RouterProperties.Health config;
    private final Ewma approval;
    private final Ewma technicalFailure;
    private final Ewma latency;

    CorridorHealth(RouterProperties.Health config) {
        this.config = config;
        double halfLifeMillis = config.halfLife().toMillis();
        this.approval = new Ewma(halfLifeMillis);
        this.technicalFailure = new Ewma(halfLifeMillis);
        this.latency = new Ewma(halfLifeMillis);
    }

    /** The issuer said yes. Decisive, and the fastest way to earn traffic back. */
    void recordApproval(long nowMillis, long latencyMillis) {
        approval.observe(nowMillis, 1);
        technicalFailure.observe(nowMillis, 0);
        latency.observe(nowMillis, latencyMillis);
    }

    /**
     * The issuer said no.
     *
     * <p>Counted against approval rate but <em>not</em> against availability: the acquirer
     * did its job. A router that demoted acquirers for relaying declines would drift
     * towards whichever bank happened to see the least risky traffic, which is a
     * property of the merchant's customers rather than of the bank.
     */
    void recordBusinessDecline(long nowMillis, long latencyMillis) {
        approval.observe(nowMillis, 0);
        technicalFailure.observe(nowMillis, 0);
        latency.observe(nowMillis, latencyMillis);
    }

    /**
     * Nobody decided anything.
     *
     * <p>Counted against availability and deliberately left out of approval rate, whose
     * denominator is decisive outcomes only.
     */
    void recordTechnicalFailure(long nowMillis, long latencyMillis) {
        technicalFailure.observe(nowMillis, 1);
        latency.observe(nowMillis, latencyMillis);
    }

    /**
     * The acquirer answered, on an operation whose verdict says nothing about approval
     * rate — a capture, a refund, a void.
     *
     * <p>Availability evidence without approval evidence. A capture timing out is a
     * genuine sign the acquirer is unreachable and belongs in the routing decision; a
     * capture <em>declined</em> is usually the platform asking for more than the
     * authorization holds, which is the platform's fault and not the acquirer's.
     */
    void recordReachable(long nowMillis, long latencyMillis) {
        technicalFailure.observe(nowMillis, 0);
        latency.observe(nowMillis, latencyMillis);
    }

    /**
     * Refused on capacity grounds.
     *
     * <p>An availability failure like any other, but its latency is excluded: a refusal at
     * the door returns in microseconds, and letting that into the latency average would
     * make a saturated acquirer look like the fastest one on the panel.
     */
    void recordThrottled(long nowMillis) {
        technicalFailure.observe(nowMillis, 1);
    }

    /** Everything the selector needs, read at one instant so the three agree. */
    public Reading readAt(long nowMillis) {
        return new Reading(
                approval.meanAt(nowMillis, config.priorApprovalRate(), config.priorWeight()),
                technicalFailure.meanAt(
                        nowMillis, config.priorTechnicalFailureRate(), config.priorWeight()),
                latency.rawMean(),
                technicalFailure.samplesAt(nowMillis));
    }

    /** Adopts a persisted opinion, capped so live evidence can overturn it quickly. */
    void restore(long nowMillis, double approvalRate, double technicalFailureRate,
            double latencyMillis, double samples) {
        double capped = Math.min(samples, config.restoredSampleCap());
        approval.seed(nowMillis, approvalRate, capped);
        technicalFailure.seed(nowMillis, technicalFailureRate, capped);
        latency.seed(nowMillis, latencyMillis, capped);
    }

    /**
     * A consistent view of one corridor's health.
     *
     * @param approvalRate         shrunk towards the prior by sample count
     * @param technicalFailureRate likewise
     * @param latencyMillis        empty until the corridor has answered at least once, so
     *                             that an unmeasured corridor is neither rewarded for
     *                             being imaginary nor punished for being new
     * @param samples              effective observations behind this reading, decayed
     */
    public record Reading(
            double approvalRate,
            double technicalFailureRate,
            OptionalDouble latencyMillis,
            double samples) {
    }
}
