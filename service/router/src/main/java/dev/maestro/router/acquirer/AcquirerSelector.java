package dev.maestro.router.acquirer;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Chooses which acquirer handles a payment on a given corridor.
 *
 * <p>A corridor is a card network and a currency — {@code VISA:AUD} — because cost,
 * capacity and health are properties of that pair rather than of an acquirer as a
 * whole (ADR-0007).
 */
public interface AcquirerSelector {

    Selection select(Request request);

    /**
     * Whether anywhere is left to send this payment.
     *
     * <p>Asked before committing to a failover rather than discovering it by catching an
     * exception on the next pass, because by then the current attempt has already been
     * written down as one the router intended to walk away from.
     */
    boolean hasCandidate(Request request);

    /**
     * @param corridor            network and currency, {@code VISA:AUD}
     * @param amountMinor         the payment's amount, because the cheapest acquirer is
     *                            not the same acquirer at every ticket size — a fixed fee
     *                            outweighs a basis-point spread on small payments and is
     *                            irrelevant on large ones
     * @param excludedAcquirerIds acquirers already tried for this payment, so a failover
     *                            does not return to one that has just failed
     */
    record Request(String corridor, long amountMinor, Set<String> excludedAcquirerIds) {

        public Request {
            excludedAcquirerIds = Set.copyOf(excludedAcquirerIds);
        }

        public static Request first(String corridor, long amountMinor) {
            return new Request(corridor, amountMinor, Set.of());
        }

        public boolean isFailover() {
            return !excludedAcquirerIds.isEmpty();
        }
    }

    /**
     * @param reason      why this acquirer was chosen, recorded on the attempt so the
     *                    decision can be explained afterwards rather than guessed at
     * @param healthScore the score at the moment of selection, frozen. Frozen because the
     *                    score will have moved by the time anyone asks, and the question
     *                    people ask is why the router chose what it chose <em>then</em>
     */
    record Selection(String acquirerId, String reason, BigDecimal healthScore) {

        /**
         * The operation follows an authorization, so it goes to the institution holding
         * it. Not a routing decision at all — sending a capture anywhere else would act
         * against a reference that does not exist there.
         */
        public static final String REASON_PINNED = "PINNED";

        /** The highest-scoring candidate. */
        public static final String REASON_BEST_SCORE = "BEST_SCORE";

        /**
         * Not the highest-scoring candidate, chosen anyway.
         *
         * <p>This is the reason that makes the system a router rather than a routing table
         * that updates itself once. Traffic here is what keeps evidence flowing about
         * acquirers the platform currently believes are worse — without which a demoted
         * acquirer can never be observed to have recovered (ADR-0007).
         */
        public static final String REASON_EXPLORATION = "EXPLORATION";

        /** A previous attempt on this payment failed technically and this is the next one. */
        public static final String REASON_FAILOVER = "FAILOVER";
    }

    /** No acquirer can serve the corridor: none configured, all excluded, or all broken. */
    class NoAcquirerAvailableException extends RuntimeException {

        public NoAcquirerAvailableException(String message) {
            super(message);
        }
    }
}
