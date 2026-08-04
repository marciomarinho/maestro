package dev.maestro.router.acquirer;

/**
 * Chooses which acquirer handles a payment on a given corridor.
 *
 * <p>A corridor is a card network and a currency — {@code VISA:AUD} — because cost,
 * capacity and health are properties of that pair rather than of an acquirer as a
 * whole (ADR-0007).
 *
 * <p>Phase 1 has one acquirer, so the interface exists mainly to name the seam. Phase 3
 * replaces the implementation with EWMA health scoring, cost weighting and a mandatory
 * exploration floor, without anything on the calling side changing.
 */
public interface AcquirerSelector {

    /**
     * @param excludedAcquirerIds acquirers already tried for this payment, so a failover
     *                            does not return to one that has just failed
     */
    Selection select(String corridor, java.util.Set<String> excludedAcquirerIds);

    /**
     * @param reason      why this acquirer was chosen, recorded on the attempt so the
     *                    decision can be explained afterwards rather than guessed at
     * @param healthScore the score at the moment of selection, frozen; null while no
     *                    health model exists
     */
    record Selection(String acquirerId, String reason, java.math.BigDecimal healthScore) {

        public static final String REASON_PINNED = "PINNED";
        public static final String REASON_BEST_SCORE = "BEST_SCORE";
        public static final String REASON_EXPLORATION = "EXPLORATION";
        public static final String REASON_FAILOVER = "FAILOVER";
    }
}
