# 0003. Money as integer minor units

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

Every value in this system that matters is a monetary amount. It crosses a JSON boundary, a Java type system, a database column, a Kafka envelope and a browser — and at each crossing there is an opportunity to lose a cent or gain one.

The classic failure is floating point. `0.1 + 0.2` is not `0.3` in IEEE 754, and a platform that computes a percentage fee on a million transactions in `double` will produce a ledger that does not balance, in a way that is maddening to trace because each individual error is invisible.

The subtler failure is an amount without a unit. A field called `amount` containing `1999` might be dollars or cents, and the two interpretations differ by a factor of a hundred. This is not hypothetical; it is one of the most common integration defects in payments.

## Decision

Money is **an integer count of minor units plus an ISO 4217 currency code**, represented by a single `Money` value type in `lib-domain`. A bare number never crosses a boundary.

- **Java:** `Money(long amountMinor, Currency currency)`. Arithmetic on mixed currencies throws. There is no implicit conversion and no ambient default currency.
- **JSON:** always the pair `amount_minor` (integer) and `currency`. The field name carries the unit, so no client can misread it.
- **Database:** `BIGINT` for the amount, `CHAR(3)` for the currency. Never `NUMERIC`, never `FLOAT`.
- **Minor-unit scale is derived from the currency**, not a constant: `JPY` has zero decimal places, `AUD` two, `BHD` three. Scale is used only when formatting for display.
- **Division states its rounding explicitly.** Fee calculation is the only division in the system; its rounding mode and remainder allocation are specified and tested.
- **Sums within a journal transaction must be exactly zero** — not within a tolerance.

`long` gives roughly ±9.2 × 10¹⁸ minor units, which is ample: a currency with three decimal places still leaves quadrillions of units of headroom.

## Consequences

**Positive.** Arithmetic is exact and total. A whole class of rounding defects cannot occur. Cross-currency mistakes fail loudly at the type level rather than silently producing a wrong number. Serialisation is unambiguous.

**Negative.** More verbose than using a primitive; a `Money` type must be constructed and unwrapped. Percentage-based fee calculation requires deliberate thought about rounding rather than a division. Both are the point: the friction is where the bugs used to be.

**Neutral.** The portal formats amounts for display using the currency's scale; the API contract stays in minor units.

## Alternatives considered

### `BigDecimal`

The usual Java answer, and genuinely exact. Rejected because it carries a scale that can silently vary (`1.5` and `1.50` compare unequal under `equals`), it permits division without stating rounding — throwing at runtime instead — and it still does not carry a currency, so the mixed-currency bug remains available. Minor units with a currency solves both problems in one type.

### `double` or `float`

Rejected outright. Inexact for decimal fractions by construction.

### An amount in major units with a scale field

More human-readable in JSON. Rejected because it moves scale into the payload where a client can get it wrong, and requires normalisation at every boundary.

### An existing money library such as Joda-Money or JSR-354

Well designed and battle-tested. Rejected because the required surface is small — construction, addition, subtraction, comparison, one rounding-explicit allocation — and because the money type is the single most important abstraction in the system. Owning it means owning its invariants, its serialisation and its tests, which is the right call for the core type of a payments platform even though it would be over-engineering for a peripheral one.

## Revisit when

Multi-currency accounts with foreign-exchange conversion are introduced. Conversion adds rate sourcing, rate freshness and rounding across currencies, and would justify revisiting whether a dedicated library carries its weight.
