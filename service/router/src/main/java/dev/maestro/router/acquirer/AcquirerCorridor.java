package dev.maestro.router.acquirer;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one acquirer charges the platform on one corridor.
 *
 * <p>These are commercial terms — the output of a negotiation, not an observation. The
 * router measures everything else about an acquirer for itself; this is the one thing it
 * has to be told.
 *
 * @param costBps        proportional component in basis points; {@code 130.00} is 1.30%
 * @param fixedFeeMinor  flat per-transaction component in the corridor's currency
 * @param enabled        false takes the corridor out of selection entirely — the manual
 *                       override for an acquirer being cut off for reasons no health
 *                       score can see, such as a contract ending
 */
public record AcquirerCorridor(
        String acquirerId,
        String corridor,
        BigDecimal costBps,
        long fixedFeeMinor,
        boolean enabled) {

    private static final BigDecimal BASIS_POINT_SCALE = BigDecimal.valueOf(10_000L);

    /**
     * What routing this payment here would cost, in minor units.
     *
     * <p>Deliberately a function of the amount rather than a single number per acquirer,
     * because the cheapest acquirer is not the same acquirer at every ticket size. On a
     * $2 payment a 5c difference in the fixed fee outweighs 45 basis points; on a $2,000
     * payment it is irrelevant. A router that compared basis points alone would send all
     * the small tickets to the wrong bank and never show it in an average.
     *
     * <p>Rounded half-up, the same convention the ledger uses (ADR-0015), so a cost
     * compared here and a fee posted later cannot disagree by a cent.
     */
    public long costMinorFor(long amountMinor) {
        BigDecimal proportional = BigDecimal.valueOf(amountMinor)
                .multiply(costBps)
                .divide(BASIS_POINT_SCALE, 0, RoundingMode.HALF_UP);
        return proportional.longValueExact() + fixedFeeMinor;
    }
}
