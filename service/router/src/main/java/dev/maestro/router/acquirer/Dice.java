package dev.maestro.router.acquirer;

/**
 * The source of randomness in routing decisions.
 *
 * <p>An interface rather than a direct call to a random number generator, for one reason:
 * the exploration floor is a probabilistic guarantee, and a probabilistic guarantee
 * asserted against real randomness produces a test that fails once a fortnight for no
 * reason anybody can reproduce. With the dice injected, a seeded generator makes the
 * traffic-shift and recovery tests exact.
 *
 * <p>The production implementation draws per thread, so thousands of concurrent
 * authorizations do not queue behind one shared generator's internal state.
 */
@FunctionalInterface
public interface Dice {

    /** A uniform draw in {@code [0, 1)}. */
    double roll();
}
