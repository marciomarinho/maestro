package dev.maestro.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStatusTest {

    @Test
    void authorizationPathIsTheOnlyRouteOutOfCreated() {
        assertThat(PaymentStatus.CREATED.allowedTransitions())
                .containsExactlyInAnyOrder(PaymentStatus.AUTHORIZING, PaymentStatus.CANCELLED);
    }

    @Test
    void anAuthorizingPaymentCanOnlyReachAnOutcome() {
        assertThat(PaymentStatus.AUTHORIZING.allowedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED, PaymentStatus.FAILED);
    }

    @Test
    void aDeclineIsFinal() {
        assertThat(PaymentStatus.DECLINED.isTerminal()).isTrue();
        assertThat(PaymentStatus.DECLINED.canTransitionTo(PaymentStatus.AUTHORIZING)).isFalse();
    }

    @Test
    void capturedFundsCanOnlyBeRefunded() {
        assertThat(PaymentStatus.CAPTURED.allowedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);
    }

    @Test
    void aPartiallyRefundedPaymentCanBeRefundedAgain() {
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED))
                .isTrue();
    }

    @Test
    void moneyCannotMoveBackwardsFromCapturedToAuthorized() {
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
    }

    @Test
    void aFailedCaptureReturnsToAuthorizedSoItCanBeRetried() {
        assertThat(PaymentStatus.CAPTURING.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void everyStatusDeclaresItsTransitions(PaymentStatus status) {
        assertThat(status.allowedTransitions()).isNotNull();
        assertThat(status.canTransitionTo(null)).isFalse();
    }

    @Test
    void terminalStatesAreExactlyThoseWithNoOutwardTransitions() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream()
                        .filter(PaymentStatus::isTerminal)
                        .toList())
                .containsExactlyInAnyOrder(
                        PaymentStatus.DECLINED,
                        PaymentStatus.FAILED,
                        PaymentStatus.VOIDED,
                        PaymentStatus.EXPIRED,
                        PaymentStatus.CANCELLED,
                        PaymentStatus.REFUNDED);
    }

    @Test
    void authorizedPredicateCoversEveryStateWhereAnAcquirerHoldsFunds() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream()
                        .filter(PaymentStatus::isAuthorized)
                        .toList())
                .containsExactlyInAnyOrder(
                        PaymentStatus.AUTHORIZED,
                        PaymentStatus.CAPTURING,
                        PaymentStatus.CAPTURED,
                        PaymentStatus.PARTIALLY_REFUNDED,
                        PaymentStatus.REFUNDED);
    }
}
