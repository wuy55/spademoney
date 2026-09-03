# ADR-0024: Reconciliation re-derives, and duplicates constraints on purpose

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

The zero-sum invariant is already enforced by a deferred constraint trigger. No
account can go negative, because every debit path checks available balance under
a lock. Checking these again in a scheduled job looks redundant.

## Decision

Independent checks that re-derive every invariant from the raw rows — 7 in the
Ledger, 6 in Payments, two of the latter crossing the service boundary over HTTP.
Exposed at `GET /reconciliation` on both services and run on a timer.

## Consequences

**Enforcement and checking fail in different ways, and that is the whole
justification.** A trigger protects rows written *through* it; it says nothing
about rows written by a migration, by a fix applied at 3am, or by a future code
path that took a shortcut. And a bug in the enforcement is invisible to the
enforcement. The value of these checks is that they agree with nothing — they
read entries and holds and do the arithmetic themselves.

The tests demonstrate this by **disabling the trigger**, writing the bad rows,
and turning it back on. Anything less would be testing that the check compiles.

The two cross-boundary checks are the bill for ADR-0016. Inside one service a
foreign key makes "this completed payment names a real ledger transaction"
impossible to violate; across the boundary nothing can enforce it, which is
exactly why `payment_limits.account_id` is not a foreign key. Referential
integrity becomes something *verified* periodically rather than *enforced*
continuously, and this is where that is paid for.

Design details that earned their place:

- **Always HTTP 200**, with a `healthy` flag in the body. A `500` would conflate
  "reconciliation is broken" with "reconciliation found something", and those
  need opposite responses.
- **Clean runs log every check by name.** A job that is silent while healthy is
  indistinguishable from one that has stopped, and the entire value of this thing
  is that its clean answer can be believed.
- **All Ledger checks run in one read-only transaction.** Otherwise they see
  different moments, and a transfer committing between two queries produces a
  phantom finding that vanishes the moment anyone investigates it.
- **Remote checks are bounded to a sample.** An unbounded diagnostic is an outage
  waiting for a busy day. This makes them a smoke alarm rather than a proof over
  all history, and the chaos script reconciles immediately after the run it cares
  about for that reason.

Two scoping lessons, both learned by getting them wrong first:

**The negative-balance check initially had no `WHERE` clause and fired on every
healthy ledger.** `CASH` is negative by construction — every funding credits a
wallet and debits `CASH`, so `CASH` carries the negative of everything ever
funded. A rule forbidding that would forbid the ledger from working. The rule
that matters is about customer money, so the check is scoped to `USER_WALLET`.

**The sweeper and relay checks need grace periods.** Both jobs are always
slightly behind by design, so a check without grace reports a job that is merely
mid-tick. A check nobody believes is worse than no check.
