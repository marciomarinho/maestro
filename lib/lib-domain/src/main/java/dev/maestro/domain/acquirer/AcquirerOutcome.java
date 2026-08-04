package dev.maestro.domain.acquirer;

/**
 * What an acquirer said, and what the router is allowed to do about it.
 *
 * <p>This type encodes the single most important rule in the platform (ADR-0012):
 * <strong>a business decline is never re-attempted on another acquirer.</strong> A
 * decline is the issuer's answer, and asking a different bank to relay the same
 * question breaches scheme rules, degrades the cardholder's future approval rates,
 * and is indistinguishable from shopping for a bank that has not yet caught up.
 *
 * <p>A technical failure is the absence of an answer. Nobody decided anything, so
 * another acquirer may legitimately be tried.
 *
 * <p>The interface is sealed so that adding an outcome forces every switch over it
 * to be revisited at compile time, rather than falling through to a default that
 * quietly does the wrong thing with someone's money.
 */
public sealed interface AcquirerOutcome {

    /** True if the same acquirer may be asked again, with the same idempotency key. */
    boolean retryableOnSameAcquirer();

    /** True if a <em>different</em> acquirer may be tried. Never true for a business decline. */
    boolean mayFailOverToAnotherAcquirer();

    /** True once no further attempt is permitted and the payment's fate is settled. */
    default boolean isFinal() {
        return !retryableOnSameAcquirer() && !mayFailOverToAnotherAcquirer();
    }

    /** The issuer approved and funds are reserved. */
    record Approved(String acquirerReference, String authorizationCode) implements AcquirerOutcome {
        @Override
        public boolean retryableOnSameAcquirer() {
            return false;
        }

        @Override
        public boolean mayFailOverToAnotherAcquirer() {
            return false;
        }
    }

    /**
     * The issuer evaluated the transaction and refused. Final, everywhere.
     */
    record BusinessDecline(DeclineCode code, String message) implements AcquirerOutcome {
        @Override
        public boolean retryableOnSameAcquirer() {
            return false;
        }

        @Override
        public boolean mayFailOverToAnotherAcquirer() {
            return false;
        }
    }

    /**
     * The issuer's or acquirer's systems failed, so no evaluation took place. A
     * decline in shape but a failure in substance — another acquirer may reach the
     * issuer by a different path.
     */
    record TechnicalFailure(String code, String message) implements AcquirerOutcome {
        @Override
        public boolean retryableOnSameAcquirer() {
            return true;
        }

        @Override
        public boolean mayFailOverToAnotherAcquirer() {
            return true;
        }
    }

    /**
     * No response arrived within the deadline, so the transaction's fate is unknown
     * — the acquirer may have authorized it.
     *
     * <p>Deliberately <em>not</em> failed over: the same acquirer is asked again with
     * the same idempotency key so that it, not us, resolves the ambiguity. Failing
     * straight over to another acquirer here is one of the few ways this system
     * could authorize the same payment twice.
     */
    record Timeout(long elapsedMillis) implements AcquirerOutcome {
        @Override
        public boolean retryableOnSameAcquirer() {
            return true;
        }

        @Override
        public boolean mayFailOverToAnotherAcquirer() {
            return false;
        }
    }

    /** The acquirer refused the request on capacity grounds. Try elsewhere, or later. */
    record Throttled(long retryAfterMillis) implements AcquirerOutcome {
        @Override
        public boolean retryableOnSameAcquirer() {
            return true;
        }

        @Override
        public boolean mayFailOverToAnotherAcquirer() {
            return true;
        }
    }
}
