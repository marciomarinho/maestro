package dev.maestro.ledger.core;

/**
 * The chart of accounts, as described in {@code docs/domain.md}.
 *
 * <p>Each kind carries its accounting type and normal balance, so the direction that
 * increases an account is a property of the account rather than something each caller has
 * to remember. Getting this backwards is how a ledger ends up balancing perfectly while
 * describing the opposite of what happened.
 */
public enum AccountKind {

    /** Funds an acquirer owes the platform for captured transactions not yet settled. */
    ACQUIRER_RECEIVABLE(AccountType.ASSET, PostingDirection.DEBIT),

    /** Funds actually received from acquirers. Unused until settlement, in Phase 5. */
    PLATFORM_CASH(AccountType.ASSET, PostingDirection.DEBIT),

    /** Funds the platform owes a merchant. */
    MERCHANT_PAYABLE(AccountType.LIABILITY, PostingDirection.CREDIT),

    /** Fees the platform has earned. */
    PLATFORM_FEE_REVENUE(AccountType.REVENUE, PostingDirection.CREDIT),

    /** Refunds sent to an acquirer, not yet reflected in a settlement file. */
    REFUND_CLEARING(AccountType.LIABILITY, PostingDirection.CREDIT);

    private final AccountType type;
    private final PostingDirection normalBalance;

    AccountKind(AccountType type, PostingDirection normalBalance) {
        this.type = type;
        this.normalBalance = normalBalance;
    }

    public AccountType type() {
        return type;
    }

    /** The direction that increases this account. */
    public PostingDirection normalBalance() {
        return normalBalance;
    }

    public enum AccountType {
        ASSET,
        LIABILITY,
        REVENUE,
        EXPENSE
    }
}
