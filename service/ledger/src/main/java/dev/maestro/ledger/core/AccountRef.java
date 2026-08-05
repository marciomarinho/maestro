package dev.maestro.ledger.core;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * A reference to an account in the chart of accounts.
 *
 * <p>Account identifiers are <em>derived</em>, not allocated: {@code merchant_payable:mch_demo:AUD}
 * is always the same account, so the ledger never has to look one up before it can post,
 * and an identifier is readable in a log line without a join. Accounts are created on
 * first use.
 *
 * <p>The currency is part of the identity because balances are per-currency. Merging two
 * currencies into one account would produce a number that means nothing.
 */
public record AccountRef(AccountKind kind, String scope, Currency currency) {

    /** Scope used by accounts that belong to the platform rather than a counterparty. */
    public static final String PLATFORM = "platform";

    public AccountRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(currency, "currency");
    }

    public static AccountRef merchantPayable(String merchantId, Currency currency) {
        return new AccountRef(AccountKind.MERCHANT_PAYABLE, merchantId, currency);
    }

    public static AccountRef acquirerReceivable(String acquirerId, Currency currency) {
        return new AccountRef(AccountKind.ACQUIRER_RECEIVABLE, acquirerId, currency);
    }

    public static AccountRef refundClearing(String acquirerId, Currency currency) {
        return new AccountRef(AccountKind.REFUND_CLEARING, acquirerId, currency);
    }

    public static AccountRef platformFeeRevenue(Currency currency) {
        return new AccountRef(AccountKind.PLATFORM_FEE_REVENUE, PLATFORM, currency);
    }

    public static AccountRef platformCash(Currency currency) {
        return new AccountRef(AccountKind.PLATFORM_CASH, PLATFORM, currency);
    }

    public String id() {
        return "%s:%s:%s".formatted(
                kind.name().toLowerCase(Locale.ROOT), scope, currency.getCurrencyCode());
    }

    /** The merchant this account belongs to, or null if it is platform- or acquirer-scoped. */
    public String merchantId() {
        return kind == AccountKind.MERCHANT_PAYABLE ? scope : null;
    }

    /** The acquirer this account belongs to, or null. */
    public String acquirerId() {
        return switch (kind) {
            case ACQUIRER_RECEIVABLE, REFUND_CLEARING -> scope;
            case MERCHANT_PAYABLE, PLATFORM_CASH, PLATFORM_FEE_REVENUE -> null;
        };
    }
}
