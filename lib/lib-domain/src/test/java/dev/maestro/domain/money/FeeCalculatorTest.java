package dev.maestro.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Currency;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    private static final Currency AUD = Currency.getInstance("AUD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    @DisplayName("the split always reconstructs the gross exactly")
    class Conservation {

        @Test
        void feePlusNetEqualsGross() {
            Money gross = Money.of(1999, AUD);

            FeeCalculator.Fee split = FeeCalculator.calculate(gross, 175, 30);

            assertThat(split.fee()).isEqualTo(Money.of(65, AUD));
            assertThat(split.net()).isEqualTo(Money.of(1934, AUD));
            assertThat(split.gross()).isEqualTo(gross);
        }

        @Test
        @DisplayName("no amount between 1c and $10,000 can lose or gain a cent")
        void conservationHoldsAcrossTheRange() {
            // The failure this guards against is invisible one transaction at a time:
            // a rounding rule that is a cent out becomes a real number over a day's volume.
            IntStream.rangeClosed(1, 1_000_000).parallel().forEach(amount -> {
                Money gross = Money.of(amount, AUD);
                FeeCalculator.Fee split = FeeCalculator.calculate(gross, 175, 0);
                assertThat(split.fee().amountMinor() + split.net().amountMinor())
                        .as("fee + net for %d", amount)
                        .isEqualTo(amount);
            });
        }
    }

    @Nested
    @DisplayName("rounding is half-up, not truncation")
    class Rounding {

        @ParameterizedTest(name = "{0} minor units at {1} bps -> fee {2}")
        @CsvSource({
                // 100 * 250bps = 2.5 exactly -> rounds up, not down to 2
                "100,  250, 3",
                // 100 * 240bps = 2.4 -> down
                "100,  240, 2",
                // 100 * 260bps = 2.6 -> up
                "100,  260, 3",
                // 1999 * 175bps = 34.9825 -> 35
                "1999, 175, 35",
                // exact multiples are untouched
                "1000, 100, 10",
        })
        void halfUpAtTheBoundary(long amountMinor, int basisPoints, long expectedFee) {
            FeeCalculator.Fee split = FeeCalculator.calculate(Money.of(amountMinor, AUD), basisPoints, 0);

            assertThat(split.fee().amountMinor()).isEqualTo(expectedFee);
        }

        @Test
        @DisplayName("truncation would under-charge; half-up does not")
        void truncationWouldHaveLostACent() {
            // Java's integer division truncates: 250/100 style cases would silently
            // round down on every .5, always in the merchant's favour and never the
            // platform's. Symmetric rounding is the point.
            long truncated = (100L * 250L) / 10_000L;
            long halfUp = FeeCalculator.calculate(Money.of(100, AUD), 250, 0).fee().amountMinor();

            assertThat(truncated).isEqualTo(2L);
            assertThat(halfUp).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("proportional refunds unwind exactly")
    class RefundProportion {

        @Test
        void refundingEverythingReturnsExactlyTheFeeCharged() {
            Money gross = Money.of(1999, AUD);
            Money fee = FeeCalculator.calculate(gross, 175, 30).fee();

            assertThat(FeeCalculator.proportionalFeeRefund(gross, fee, gross)).isEqualTo(fee);
        }

        @Test
        void refundingHalfReturnsHalfTheFee() {
            Money gross = Money.of(1000, AUD);
            Money fee = Money.of(50, AUD);

            assertThat(FeeCalculator.proportionalFeeRefund(gross, fee, Money.of(500, AUD)))
                    .isEqualTo(Money.of(25, AUD));
        }

        @Test
        void refundingNothingReturnsNothing() {
            assertThat(FeeCalculator.proportionalFeeRefund(
                            Money.of(1000, AUD), Money.of(50, AUD), Money.zero(AUD)))
                    .isEqualTo(Money.zero(AUD));
        }

        @Test
        void refundingMoreThanWasCapturedIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FeeCalculator.proportionalFeeRefund(
                            Money.of(1000, AUD), Money.of(50, AUD), Money.of(1001, AUD)));
        }
    }

    @Nested
    @DisplayName("currencies without minor units")
    class ZeroDecimalCurrencies {

        @Test
        void yenIsWholeUnitsAndStillExact() {
            // JPY has no minor unit, so 1 is one yen. The arithmetic is unchanged —
            // scale only ever matters when formatting for display.
            FeeCalculator.Fee split = FeeCalculator.calculate(Money.of(5000, JPY), 175, 0);

            assertThat(split.fee()).isEqualTo(Money.of(88, JPY));
            assertThat(split.gross()).isEqualTo(Money.of(5000, JPY));
        }
    }

    @Nested
    @DisplayName("inputs that would produce nonsense are rejected")
    class Validation {

        @Test
        void aFeeLargerThanTheCaptureIsRejected() {
            // Otherwise the merchant's net goes negative and the ledger records the
            // platform taking more than the customer paid.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FeeCalculator.calculate(Money.of(10, AUD), 0, 50))
                    .withMessageContaining("exceeds the captured amount");
        }

        @Test
        void negativeRatesAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FeeCalculator.calculate(Money.of(1000, AUD), -1, 0));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FeeCalculator.calculate(Money.of(1000, AUD), 175, -1));
        }

        @Test
        void chargingAFeeOnNothingIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FeeCalculator.calculate(Money.zero(AUD), 175, 30));
        }
    }
}
