package dev.maestro.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency AUD = Currency.getInstance("AUD");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        void addingDifferentCurrenciesThrows() {
            Money aud = Money.of(1000, AUD);
            Money usd = Money.of(1000, USD);

            assertThatExceptionOfType(Money.CurrencyMismatchException.class)
                    .isThrownBy(() -> aud.plus(usd))
                    .withMessageContaining("AUD")
                    .withMessageContaining("USD");
        }

        @Test
        void subtractingDifferentCurrenciesThrows() {
            assertThatExceptionOfType(Money.CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(1000, AUD).minus(Money.of(1, USD)));
        }

        @Test
        void comparingDifferentCurrenciesThrows() {
            assertThatExceptionOfType(Money.CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(1000, AUD).compareTo(Money.of(1000, USD)));
        }

        @Test
        void unknownCurrencyCodeIsRejectedRatherThanDefaulted() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of(100, "XYZ"))
                    .withMessageContaining("ISO 4217");
        }
    }

    @Nested
    @DisplayName("exact arithmetic")
    class ExactArithmetic {

        @Test
        void additionIsExactWhereFloatingPointWouldNotBe() {
            // 0.10 + 0.20 in IEEE 754 doubles is 0.30000000000000004.
            Money tenCents = Money.of(10, AUD);
            Money twentyCents = Money.of(20, AUD);

            assertThat(tenCents.plus(twentyCents)).isEqualTo(Money.of(30, AUD));
        }

        @Test
        void repeatedAdditionDoesNotAccumulateError() {
            Money total = Money.zero(AUD);
            for (int i = 0; i < 1_000_000; i++) {
                total = total.plus(Money.of(1, AUD));
            }

            assertThat(total.amountMinor()).isEqualTo(1_000_000L);
        }

        @Test
        void overflowThrowsRatherThanWrappingSilently() {
            Money huge = Money.of(Long.MAX_VALUE, AUD);

            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> huge.plus(Money.of(1, AUD)));
        }

        @Test
        void subtractionCanProduceNegativeAmounts() {
            assertThat(Money.of(500, AUD).minus(Money.of(800, AUD)))
                    .isEqualTo(Money.of(-300, AUD));
        }
    }

    @Nested
    @DisplayName("display formatting derives scale from the currency")
    class DisplayFormatting {

        @Test
        void twoDecimalPlaceCurrency() {
            assertThat(Money.of(1999, AUD).toDisplayString()).isEqualTo("19.99 AUD");
        }

        @Test
        void zeroDecimalPlaceCurrency() {
            assertThat(Money.of(1999, JPY).toDisplayString()).isEqualTo("1999 JPY");
        }

        @Test
        void amountsBelowOneMajorUnitKeepTheirLeadingZero() {
            assertThat(Money.of(5, AUD).toDisplayString()).isEqualTo("0.05 AUD");
        }

        @Test
        void negativeAmountsBelowOneMajorUnitKeepTheirSign() {
            assertThat(Money.of(-5, AUD).toDisplayString()).isEqualTo("-0.05 AUD");
        }
    }

    @Test
    void equalityIncludesCurrency() {
        assertThat(Money.of(1000, AUD)).isNotEqualTo(Money.of(1000, USD));
        assertThat(Money.of(1000, AUD)).isEqualTo(Money.of(1000, "AUD"));
    }

    @Test
    void signPredicates() {
        assertThat(Money.of(1, AUD).isPositive()).isTrue();
        assertThat(Money.zero(AUD).isZero()).isTrue();
        assertThat(Money.of(-1, AUD).isNegative()).isTrue();
    }
}
