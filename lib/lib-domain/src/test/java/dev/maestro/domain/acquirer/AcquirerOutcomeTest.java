package dev.maestro.domain.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the rule in ADR-0012. If any of these fail, the platform has started
 * shopping declines around acquirers.
 */
class AcquirerOutcomeTest {

    @ParameterizedTest
    @EnumSource(DeclineCode.class)
    @DisplayName("no business decline may ever be re-attempted on another acquirer")
    void businessDeclinesNeverFailOver(DeclineCode code) {
        AcquirerOutcome outcome = new AcquirerOutcome.BusinessDecline(code, "declined");

        assertThat(outcome.mayFailOverToAnotherAcquirer()).isFalse();
        assertThat(outcome.retryableOnSameAcquirer()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
    }

    @Test
    @DisplayName("a technical failure may be tried elsewhere, because nobody decided anything")
    void technicalFailuresMayFailOver() {
        AcquirerOutcome outcome = new AcquirerOutcome.TechnicalFailure("ISSUER_UNAVAILABLE", "down");

        assertThat(outcome.mayFailOverToAnotherAcquirer()).isTrue();
        assertThat(outcome.isFinal()).isFalse();
    }

    @Test
    @DisplayName("a timeout is retried on the same acquirer, never failed over")
    void timeoutsRetryTheSameAcquirerToResolveTheAmbiguity() {
        // The acquirer may already have authorized. Sending the same transaction to a
        // different bank before that is resolved risks authorizing it twice.
        AcquirerOutcome outcome = new AcquirerOutcome.Timeout(3_000L);

        assertThat(outcome.retryableOnSameAcquirer()).isTrue();
        assertThat(outcome.mayFailOverToAnotherAcquirer()).isFalse();
    }

    @Test
    void throttlingMayBeAbsorbedByAnotherAcquirer() {
        AcquirerOutcome outcome = new AcquirerOutcome.Throttled(250L);

        assertThat(outcome.mayFailOverToAnotherAcquirer()).isTrue();
    }

    @Test
    void anApprovalEndsTheAttemptChain() {
        AcquirerOutcome outcome = new AcquirerOutcome.Approved("acq_ref_1", "AUTH123");

        assertThat(outcome.isFinal()).isTrue();
    }

    @Test
    @DisplayName("switching over outcomes is exhaustive, so a new one cannot be silently ignored")
    void switchingIsExhaustive() {
        // This compiles only while every permitted subtype is handled. Adding a new
        // outcome breaks the build here rather than falling through a default branch.
        AcquirerOutcome outcome = new AcquirerOutcome.Approved("acq_ref_1", "AUTH123");

        String description = switch (outcome) {
            case AcquirerOutcome.Approved a -> "approved " + a.acquirerReference();
            case AcquirerOutcome.BusinessDecline d -> "declined " + d.code();
            case AcquirerOutcome.TechnicalFailure f -> "failed " + f.code();
            case AcquirerOutcome.Timeout t -> "timeout after " + t.elapsedMillis() + "ms";
            case AcquirerOutcome.Throttled t -> "throttled for " + t.retryAfterMillis() + "ms";
        };

        assertThat(description).isEqualTo("approved acq_ref_1");
    }

    @Test
    void codesWhoseConditionCannotChangeAreNotRetryableLater() {
        assertThat(DeclineCode.STOLEN_CARD.retryLaterPermitted()).isFalse();
        assertThat(DeclineCode.SUSPECTED_FRAUD.retryLaterPermitted()).isFalse();
        assertThat(DeclineCode.EXPIRED_CARD.retryLaterPermitted()).isFalse();
        assertThat(DeclineCode.INSUFFICIENT_FUNDS.retryLaterPermitted()).isTrue();
    }
}
