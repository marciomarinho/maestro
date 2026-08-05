package dev.maestro.ledger.core;

/**
 * Which side of the journal a posting sits on.
 *
 * <p>Amounts are always positive; the direction carries the sign. A signed amount would
 * allow a "negative debit", which is a credit written in a way that is harder to read and
 * impossible to sum without a special case.
 *
 * <p>Balances are stored debit-positive, so a liability like {@code merchant_payable}
 * shows as a negative number and a debit reduces what the platform owes.
 */
public enum PostingDirection {

    DEBIT(1),
    CREDIT(-1);

    private final int sign;

    PostingDirection(int sign) {
        this.sign = sign;
    }

    /** Applies the debit-positive convention, for summing and for balance updates. */
    public long signed(long amountMinor) {
        return sign * amountMinor;
    }
}
