package dev.maestro.domain.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An exact monetary amount: an integer count of minor units paired with a currency.
 *
 * <p>Amounts are never floating point and never travel without their currency
 * (ADR-0003). Mixed-currency arithmetic throws rather than silently producing a
 * number that is wrong by an exchange rate.
 *
 * <p>{@code Money.of(1999, "AUD")} is $19.99. The number of minor units in a major
 * unit is a property of the currency — {@code JPY} has none, {@code BHD} has three —
 * and is consulted only when formatting for display.
 */
public record Money(long amountMinor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, parseCurrency(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Parses an ISO 4217 alphabetic code, rejecting unknown codes rather than
     * defaulting. A currency the JVM does not recognise is a data error, not a
     * value to guess at.
     */
    public static Currency parseCurrency(String code) {
        Objects.requireNonNull(code, "currency code must not be null");
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid ISO 4217 currency code: " + code, e);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public boolean isZero() {
        return amountMinor == 0L;
    }

    public boolean isPositive() {
        return amountMinor > 0L;
    }

    public boolean isNegative() {
        return amountMinor < 0L;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    /** Human-readable form, e.g. {@code 19.99 AUD}. For display only, never for transport. */
    public String toDisplayString() {
        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits <= 0) {
            return amountMinor + " " + currency.getCurrencyCode();
        }
        long divisor = (long) Math.pow(10, fractionDigits);
        long major = amountMinor / divisor;
        long minor = Math.abs(amountMinor % divisor);
        String sign = (amountMinor < 0 && major == 0) ? "-" : "";
        String pattern = "%s%d.%0" + fractionDigits + "d %s";
        return pattern.formatted(sign, major, minor, currency.getCurrencyCode());
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    /** Thrown when an operation would combine amounts in different currencies. */
    public static final class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(Currency left, Currency right) {
            super("Cannot combine %s with %s: currencies must match"
                    .formatted(left.getCurrencyCode(), right.getCurrencyCode()));
        }
    }
}
