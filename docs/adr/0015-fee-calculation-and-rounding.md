# 0015. Fee calculation: integer arithmetic, half-up, merchant absorbs the remainder

- **Status:** Accepted
- **Date:** 2026-08-05

## Context

Capturing a payment splits it: the platform keeps a fee, the merchant is owed the rest.
That split is the only division in the entire platform, and division is where money goes
missing.

Three questions have to be answered, and answering them by accident is how a ledger ends
up a cent out on thousands of transactions:

1. **What arithmetic?** Percentages do not divide evenly into minor units. 1.75% of $19.99
   is 34.9825 cents.
2. **Which way does it round?** Java's integer division truncates toward zero, which rounds
   *every* fractional cent down — always in the merchant's favour, never the platform's.
   That is a systematic bias, not a rounding error.
3. **Who absorbs the remainder?** If the fee and the net are each rounded independently,
   they will sometimes not add back up to the gross, and the ledger will refuse to balance.

There is also a fourth question that only appears later: when a payment is partly refunded,
how much of the fee comes back?

## Decision

**All fee arithmetic is integer arithmetic on minor units** (ADR-0003), implemented as a
pure function in `lib-domain` with no framework dependency, so it is unit-testable in
microseconds and cannot quietly acquire one.

**Pricing is basis points plus a fixed component.** `fee = round(gross × bps ÷ 10000) + fixed`.
On $19.99 at 175 bps + 30c the fee is 65c and the merchant nets $19.34.

**Rounding is half away from zero**, implemented as `floorDiv(2n + d, 2d)` rather than
`/`. Half-up is what card schemes and finance teams expect, and — more importantly — it is
symmetric, so fees do not drift systematically in either party's favour.

**The merchant absorbs the remainder**, and the net is *derived*: `net = gross − fee`. Only
one of the two numbers is ever rounded. This makes `fee + net = gross` true by construction
rather than by luck, which is what lets the capture posting balance without a plug figure.

**Partial refunds return the fee in proportion**, priced from what that payment was
actually charged rather than from today's schedule — a merchant whose pricing changed after
a capture must still be unwound on the original terms. Refunding *everything* returns
exactly the fee charged, short-circuiting the proportion so the common case is exact rather
than approximately exact.

**A fee that would exceed the captured amount is rejected**, because the alternative is a
negative net: the ledger recording the platform taking more than the customer paid.

## Consequences

**Positive.** `fee + net` reconstructs the gross for every amount — asserted across a
million of them in `FeeCalculatorTest`. No systematic bias in either direction. A sequence
of partial refunds unwinds to the original fee. The whole thing is a pure function, so the
tests are fast and total.

**Negative.** Proportional refund arithmetic can leave a single minor unit of fee revenue
behind across an unusual sequence of partial refunds — bounded, visible in the ledger, and
preferable to the alternative, which is refunds that do not sum back to the capture.
Pricing is also simpler than real interchange-plus; that is a separate deliberate cut
recorded in the backlog.

**Neutral.** Zero-decimal currencies need no special handling. Minor units are minor units;
scale only matters when formatting for display.

## Alternatives considered

### `BigDecimal` with an explicit `RoundingMode`

The conventional Java answer, and genuinely exact. Rejected for the reasons in ADR-0003 —
a scale that varies silently, division that throws at runtime rather than being made
explicit, and no currency in the type — and because the operation needed here is a single
rounded division that integer arithmetic expresses in one line with no object allocation.

### Truncation, because it is what `/` does

Rejected. It biases every fractional cent the same way. `FeeCalculatorTest` contains a test
that asserts the difference explicitly, so nobody can reintroduce it by "simplifying" the
division.

### Banker's rounding (half to even)

Genuinely defensible, and it eliminates bias more completely than half-up across a large
population. Rejected because it surprises merchants reconciling by hand — 2.5 rounding to 2
looks like a defect to anyone who has not met the convention — and because half-up is what
the surrounding industry uses. Consistency with the rest of the payments world is worth
more here than a marginally better statistical property.

### Rounding the merchant's net and deriving the fee

The mirror image, and equally self-consistent. Rejected because it means the platform's
revenue is the residual of someone else's rounding, which makes revenue reporting harder to
explain than it needs to be.

## Revisit when

Interchange-plus pricing is introduced, or a merchant contract requires a rounding rule
this one cannot express.
