# ADR-0001: Money as integer minor units

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Spencer Wu

## Context

Every amount in this system is currency. Binary floating point cannot represent
most decimal fractions exactly, so `0.1 + 0.2` is not `0.3`, and a ledger built
on `double` accumulates drift that shows up as unexplainable pennies in
reconciliation months later. `BigDecimal` represents the values correctly but
carries a scale that can differ between two objects holding the same amount, so
equality and storage both become questions rather than facts.

A currency also has an intrinsic precision. USD has two decimal places, JPY has
zero, and a type that lets you write three decimal places of USD is a type that
lets a rounding decision happen somewhere nobody reviewed.

## Decision

Money is a `record Money(long amountMinor, Currency currency)`. The amount is
always an integer count of the currency's smallest unit, and the currency is
always an ISO-4217 `java.util.Currency`. There is no float or double constructor
and no way to build one.

The constructor rejects a non-positive amount and a null currency, so an
instance cannot exist in an invalid state. Direction is not the amount's job:
sign lives in the entry's `DEBIT`/`CREDIT` column, which is why the schema can
also assert `amount_minor > 0`.

Decimal input from the wire goes through `Money.parse`, which parses via
`BigDecimal` and then rejects, in order: scientific notation, non-positive
values, a scale finer than the currency allows, and a magnitude that will not
fit in a `long`. Arithmetic uses `Math.addExact`, so an overflow throws rather
than wrapping into a negative balance. `plus` refuses to add two different
currencies.

## Consequences

There is no rounding drift, because there is no rounding: every amount is
already an integer in the unit the currency actually settles in.

Currency mismatch becomes a loud failure at the type level rather than a silent
coercion. The cost is that cross-currency work is not merely unimplemented, it is
actively rejected — there is no implicit conversion anywhere. Adding FX means
adding an explicit conversion step with a rate, a timestamp, and its own ledger
treatment, which is the correct shape for that problem but is real work rather
than a flag.

`long` caps a single amount near nine quintillion minor units. That is far above
anything this system will hold, and `parse` reports the overflow explicitly
rather than truncating, so the ceiling is a documented boundary rather than a
lurking defect.

Callers must think in minor units. That is a small ongoing tax on readability,
paid to remove an entire class of correctness bug, and `toDecimalString` exists
so the tax is only paid inside the domain and never at the API boundary.

See also [ADR-0014](0014-no-negative-balances.md), which relies on amounts being
exact for `balance >= 0` to be a provable invariant rather than an approximate
one.
