package dev.maestro.domain.money;

import java.util.Objects;

/**
 * Splits a captured amount into the platform's fee and the merchant's net.
 *
 * <p>This is the only division in the platform, and it is where cents go missing in
 * systems that are careless about rounding. Everything here is integer arithmetic on
 * minor units (ADR-0003); no {@code double}, no {@code BigDecimal}, and an ArchUnit rule
 * fails the build if a floating-point field appears anywhere near it.
 *
 * <p>Pricing is the conventional shape: a proportion expressed in basis points plus a
 * fixed per-transaction component. On $19.99 at 175 bps + 30c the fee is 65c, not
 * 64.9825c, and the merchant nets $19.34.
 *
 * <p><strong>Rounding is half-up, and the merchant absorbs the remainder.</strong> Half-up
 * is the convention card schemes and finance teams expect, and it is symmetric, so fees
 * do not systematically drift in the platform's favour. Stating who absorbs the
 * remainder matters because the alternative — rounding the merchant's net independently
 * — lets fee and net disagree with gross by a cent, which is precisely the class of
 * discrepancy reconciliation exists to catch.
 */
public final class FeeCalculator {

    private static final long BASIS_POINT_SCALE = 10_000L;

    private FeeCalculator() {
    }

    /**
     * @param gross     the captured amount
     * @param basisPoints proportional component; 175 means 1.75%
     * @param fixedMinor  flat component in the same currency's minor units
     * @throws IllegalArgumentException if the fee would exceed the captured amount, which
     *                                  would make the merchant's net negative
     */
    public static Fee calculate(Money gross, int basisPoints, long fixedMinor) {
        Objects.requireNonNull(gross, "gross must not be null");
        if (basisPoints < 0) {
            throw new IllegalArgumentException("basisPoints must not be negative: " + basisPoints);
        }
        if (fixedMinor < 0) {
            throw new IllegalArgumentException("fixedMinor must not be negative: " + fixedMinor);
        }
        if (!gross.isPositive()) {
            throw new IllegalArgumentException("Cannot charge a fee on a non-positive amount");
        }

        long proportional = halfUpDivide(
                Math.multiplyExact(gross.amountMinor(), (long) basisPoints), BASIS_POINT_SCALE);
        long feeMinor = Math.addExact(proportional, fixedMinor);

        if (feeMinor > gross.amountMinor()) {
            throw new IllegalArgumentException(
                    "Fee of %d exceeds the captured amount of %d %s"
                            .formatted(feeMinor, gross.amountMinor(), gross.currency().getCurrencyCode()));
        }

        Money fee = Money.of(feeMinor, gross.currency());
        return new Fee(fee, gross.minus(fee));
    }

    /**
     * Proportionally reduces a fee when only part of a capture is refunded.
     *
     * <p>Refunding half a payment returns half the fee. The fixed component is included
     * in the proportion rather than returned whole or kept whole, because either choice
     * makes a sequence of partial refunds fail to sum back to the original fee — and a
     * ledger that cannot be unwound exactly is a ledger that will not reconcile.
     */
    public static Money proportionalFeeRefund(Money originalGross, Money originalFee, Money refunded) {
        Objects.requireNonNull(refunded, "refunded must not be null");
        if (refunded.amountMinor() > originalGross.amountMinor()) {
            throw new IllegalArgumentException("Refund exceeds the captured amount");
        }
        if (refunded.amountMinor() == originalGross.amountMinor()) {
            // Refunding everything returns exactly the fee charged, with no rounding at
            // all — the common case must be exact.
            return originalFee;
        }
        long refundedFee = halfUpDivide(
                Math.multiplyExact(originalFee.amountMinor(), refunded.amountMinor()),
                originalGross.amountMinor());
        return Money.of(refundedFee, originalGross.currency());
    }

    /**
     * Integer division rounding half away from zero.
     *
     * <p>Java's {@code /} truncates towards zero, which would quietly under-charge on
     * every fee ending in .5 and over hundreds of thousands of transactions becomes a
     * real number.
     */
    private static long halfUpDivide(long numerator, long denominator) {
        if (denominator == 0L) {
            throw new ArithmeticException("Division by zero in fee calculation");
        }
        long doubled = Math.addExact(Math.multiplyExact(numerator, 2L), denominator);
        return Math.floorDiv(doubled, Math.multiplyExact(denominator, 2L));
    }

    /**
     * The split of a captured amount.
     *
     * @param fee what the platform earns
     * @param net what the merchant is owed; {@code fee + net} is always exactly the gross
     */
    public record Fee(Money fee, Money net) {

        public Fee {
            Objects.requireNonNull(fee, "fee");
            Objects.requireNonNull(net, "net");
        }

        public Money gross() {
            return fee.plus(net);
        }
    }
}
