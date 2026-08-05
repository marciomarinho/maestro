package dev.maestro.ledger.core;

import dev.maestro.domain.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * A balanced set of postings, ready to be recorded.
 *
 * <p>The balance rule is checked here <em>and</em> at the database. The database check is
 * the guarantee — it cannot be bypassed by any code path (see {@code V1__ledger_schema.sql}).
 * This one exists so a programming mistake fails immediately, at the line that built the
 * entry, rather than as a constraint violation at commit with no indication of which
 * entry was wrong.
 *
 * <p>Zero-amount lines are dropped rather than rejected. A fee of zero is a legitimate
 * commercial arrangement, and a posting of zero carries no information while violating
 * the positive-amount constraint.
 */
public final class JournalEntry {

    private final String sourceEventId;
    private final TransactionType type;
    private final String paymentId;
    private final String reference;
    private final Currency currency;
    private final Instant occurredAt;
    private final List<Line> lines;

    private JournalEntry(Builder builder) {
        this.sourceEventId = builder.sourceEventId;
        this.type = builder.type;
        this.paymentId = builder.paymentId;
        this.reference = builder.reference;
        this.currency = builder.currency;
        this.occurredAt = builder.occurredAt;
        this.lines = List.copyOf(builder.lines);
    }

    public static Builder forEvent(String sourceEventId, TransactionType type, Instant occurredAt) {
        return new Builder(sourceEventId, type, occurredAt);
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public TransactionType type() {
        return type;
    }

    public String paymentId() {
        return paymentId;
    }

    public String reference() {
        return reference;
    }

    public Currency currency() {
        return currency;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public List<Line> lines() {
        return lines;
    }

    /** One posting: an account, a side, and an amount. */
    public record Line(AccountRef account, PostingDirection direction, Money amount) {
    }

    public enum TransactionType {
        CAPTURE,
        REFUND,
        SETTLEMENT,
        PAYOUT,
        ADJUSTMENT,
        REVERSAL
    }

    public static final class Builder {

        private final String sourceEventId;
        private final TransactionType type;
        private final Instant occurredAt;
        private final List<Line> lines = new ArrayList<>();
        private String paymentId;
        private String reference;
        private Currency currency;

        private Builder(String sourceEventId, TransactionType type, Instant occurredAt) {
            this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
            this.type = Objects.requireNonNull(type, "type");
            this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }

        public Builder payment(String id) {
            this.paymentId = id;
            return this;
        }

        public Builder reference(String value) {
            this.reference = value;
            return this;
        }

        public Builder debit(AccountRef account, Money amount) {
            return line(account, PostingDirection.DEBIT, amount);
        }

        public Builder credit(AccountRef account, Money amount) {
            return line(account, PostingDirection.CREDIT, amount);
        }

        private Builder line(AccountRef account, PostingDirection direction, Money amount) {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(amount, "amount");
            if (amount.isNegative()) {
                throw new IllegalArgumentException(
                        "Posting amounts are positive; the direction carries the sign");
            }
            if (amount.isZero()) {
                return this;
            }
            if (!account.currency().equals(amount.currency())) {
                throw new IllegalArgumentException(
                        "Posting %s in %s to an account denominated in %s".formatted(
                                amount.toDisplayString(),
                                amount.currency().getCurrencyCode(),
                                account.currency().getCurrencyCode()));
            }
            if (currency == null) {
                currency = amount.currency();
            } else if (!currency.equals(amount.currency())) {
                throw new IllegalArgumentException(
                        "A journal transaction cannot mix %s and %s".formatted(
                                currency.getCurrencyCode(), amount.currency().getCurrencyCode()));
            }
            lines.add(new Line(account, direction, amount));
            return this;
        }

        public JournalEntry build() {
            if (lines.size() < 2) {
                throw new IllegalStateException(
                        "A journal transaction needs at least two postings; got " + lines.size());
            }
            long imbalance = lines.stream()
                    .mapToLong(line -> line.direction().signed(line.amount().amountMinor()))
                    .sum();
            if (imbalance != 0L) {
                throw new IllegalStateException(
                        "Journal entry for event %s does not balance: debits minus credits = %d"
                                .formatted(sourceEventId, imbalance));
            }
            return new JournalEntry(this);
        }
    }
}
