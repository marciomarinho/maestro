package dev.maestro.router.health;

import java.util.OptionalDouble;

/**
 * A time-decayed mean, and the effective number of samples behind it.
 *
 * <p>Both come from the same pair of numbers. Every observation adds {@code x} to a
 * weighted sum and {@code 1} to a weight, and both decay towards zero with the same
 * half-life. The mean is {@code sum / weight}; the weight <em>is</em> the effective
 * sample count. That is the whole mechanism, and it is why confidence needs no separate
 * bookkeeping: evidence that has aged out of the mean has aged out of the confidence in
 * it at exactly the same rate.
 *
 * <p><strong>Decay is by elapsed time, not by observation count.</strong> The distinction
 * matters more than it looks. A count-based window on a corridor receiving four requests
 * a minute would still be reporting an acquirer's state from twenty minutes ago, which is
 * precisely the corridor where a stale opinion is most likely to be wrong and least likely
 * to be corrected. Time-decay means a quiet corridor's opinion fades whether or not
 * traffic arrives to fade it.
 *
 * <p>The half-life is the single tunable. At thirty seconds, an acquirer that starts
 * failing every request has lost roughly half its former evidence within thirty seconds
 * and almost all of it within two minutes — fast enough that a brownout registers while
 * it is still happening, slow enough that a run of four unlucky declines does not.
 *
 * <p>Instances are mutated from many request threads at once, so every method that
 * touches state is synchronised. The critical sections are a handful of arithmetic
 * operations on one object per acquirer-corridor; contention is not a concern at any
 * volume this platform will see.
 */
final class Ewma {

    private static final double LN_2 = Math.log(2);

    private final double halfLifeMillis;

    private double weightedSum;
    private double weight;
    private long lastUpdateMillis;
    private boolean seeded;

    Ewma(double halfLifeMillis) {
        if (halfLifeMillis <= 0) {
            throw new IllegalArgumentException("Half-life must be positive: " + halfLifeMillis);
        }
        this.halfLifeMillis = halfLifeMillis;
    }

    /** Records one observation at the given time. */
    synchronized void observe(long nowMillis, double value) {
        decayTo(nowMillis);
        weightedSum += value;
        weight += 1;
    }

    /**
     * Adopts a mean and a sample count observed elsewhere — a snapshot read at startup.
     *
     * <p>Deliberately capped when applied, by the caller, to a modest number of samples:
     * a restored opinion should be a starting hint that live traffic can overturn within
     * seconds, not a conviction that takes minutes to shift.
     */
    synchronized void seed(long nowMillis, double mean, double samples) {
        weightedSum = mean * samples;
        weight = samples;
        lastUpdateMillis = nowMillis;
        seeded = true;
    }

    /**
     * The mean as of now, shrunk towards a prior.
     *
     * <p>Shrinkage is what stops a corridor with four samples from having a strong
     * opinion about itself. The prior is worth {@code priorWeight} imaginary
     * observations, so a corridor with two real samples is still mostly prior and a
     * corridor with two hundred is barely affected — and the transition between the two
     * needs no threshold, no minimum-sample rule and no special case.
     *
     * <p>Read-side decay is applied without mutating, so a corridor that has gone quiet
     * loses confidence as time passes rather than at whatever moment it next sees
     * traffic. Without this, an acquirer that failed hard and then received nothing would
     * hold its damning score indefinitely.
     */
    synchronized double meanAt(long nowMillis, double prior, double priorWeight) {
        double factor = decayFactor(nowMillis);
        return (weightedSum * factor + prior * priorWeight) / (weight * factor + priorWeight);
    }

    /**
     * The mean with no prior, or empty when nothing has been observed.
     *
     * <p>No decay is applied, because none is needed: decay scales the sum and the weight
     * by the same factor, so it cancels in their ratio. Time changes how much this mean is
     * <em>worth</em> — see {@link #samplesAt} — but not what it is.
     */
    synchronized OptionalDouble rawMean() {
        return weight <= 0 ? OptionalDouble.empty() : OptionalDouble.of(weightedSum / weight);
    }

    /** Effective samples behind the mean right now. Fractional, and that is the point. */
    synchronized double samplesAt(long nowMillis) {
        return weight * decayFactor(nowMillis);
    }

    private void decayTo(long nowMillis) {
        if (!seeded) {
            seeded = true;
            lastUpdateMillis = nowMillis;
            return;
        }
        double factor = decayFactor(nowMillis);
        weightedSum *= factor;
        weight *= factor;
        lastUpdateMillis = nowMillis;
    }

    private double decayFactor(long nowMillis) {
        if (!seeded) {
            return 1;
        }
        long elapsed = nowMillis - lastUpdateMillis;
        if (elapsed <= 0) {
            // A clock that went backwards, or two observations inside the same
            // millisecond. Neither is a reason to inflate the evidence.
            return 1;
        }
        return Math.exp(-LN_2 * elapsed / halfLifeMillis);
    }
}
