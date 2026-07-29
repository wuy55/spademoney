# ADR-0012: Expiry is a predicate, not a scheduled job

- **Status:** Accepted
- **Date:** 2026-07-26
- **Deciders:** Spencer Wu

## Context

Every hold carries an `expires_at`. When that moment passes the reservation is
over and the funds are spendable again. Something has to make that true.

The obvious implementation is a sweeper: a scheduled job that finds lapsed holds
and flips them to `EXPIRED`. Correctness then depends on the job having run.

The alternative is that every reader filters on the deadline directly, and the job
— if there is one — only relabels rows that already stopped counting.

## Decision

**The predicate is authoritative.** The deadline is enforced everywhere it
matters, at the moment it matters:

- `AccountBalances.held()` sums holds `WHERE status = 'ACTIVE' AND expires_at >
  now()`.
- Capture's compare-and-set carries the same predicate:
  `WHERE id = ? AND status = 'ACTIVE' AND expires_at > now()`.
- `HoldExpirySweeper` only relabels `ACTIVE` rows whose deadline has passed. It
  changes no balance.

`expires_at` is computed by the database at insert — `now() + (? * interval '1
second')` — not by the JVM. Every comparison against it also evaluates `now()` on
the Postgres clock, so one clock owns both sides of the comparison. Deriving the
deadline from the application clock would mean two machines' clocks decide when an
authorization lapses, and a few seconds of drift would silently lengthen or
shorten every hold.

## Consequences

A paused, crashed, or never-deployed sweeper **cannot become a money bug**.
Balances are correct the instant a hold lapses. A test asserts that sweeping
changes no balance at all, which is the property worth pinning: if that test ever
fails, correctness has quietly migrated into the job.

Because the job is not load-bearing it is free to be lazy. It uses a bounded batch
and `FOR UPDATE SKIP LOCKED`, so it never blocks a capture or void that is
mid-flight on a row, and whatever it skips it collects next tick. Skipping is free
precisely because the balance was right the whole time either way. What the sweep
buys is that `EXPIRED` becomes observable for reporting, and the partial indexes
on `status = 'ACTIVE'` stay small.

**The corollary is the load-bearing part, and it is where I got this wrong first.**
Since a lapsed hold still *reads* `ACTIVE` until it is swept, any write path keyed
on status alone is incorrect. Capture originally checked `status = 'ACTIVE'` and
nothing else. That is wrong in the most expensive direction available: a hold that
lapsed but has not been swept still reads `ACTIVE`, while the funds it used to
reserve are already spendable and may already have been spent by another transfer.
Capturing on status alone would post entries with nothing behind them and drive
the payer negative — money created from nothing.

Adding `expires_at > now()` to the compare-and-set is the fix, and it is pinned by
a test named for exactly what it prevents:
`aLateCaptureCannotMintMoneyAfterTheFundsWereSpent`.

Reporting inherits the same rule. "Active and lapsed" must be treated as expired
by any consumer, because `EXPIRED` only becomes observable after a sweep. A
dashboard that groups by `status` alone will overstate outstanding authorizations
by however far the sweeper is behind.

This is the general shape worth carrying elsewhere: when a scheduled job and a
predicate can each express a rule, put correctness in the predicate and let the
job be housekeeping. The version where the job is authoritative fails silently,
and it fails while the graphs look fine.
