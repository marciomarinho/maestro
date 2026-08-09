package dev.maestro.router.acquirer;

import java.util.List;

/**
 * Where the acquiring agreements come from.
 *
 * <p>A seam, so the routing decision can be exercised against a stated set of acquirers
 * and prices rather than against a database. The claims this phase makes — traffic moves
 * away from a failing acquirer, a healed one wins it back — are claims about arithmetic
 * and elapsed time. Proving them through a container would test PostgreSQL's ability to
 * return six rows, slowly, and prove the interesting part by implication.
 */
@FunctionalInterface
public interface CorridorCatalogue {

    /** Every enabled acquirer that can serve the corridor. */
    List<AcquirerCorridor> candidatesFor(String corridor);
}
