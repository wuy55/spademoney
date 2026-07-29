# ADR-0011: Holds are state, not ledger entries

- **Status:** Accepted
- **Date:** 2026-07-26
- **Deciders:** Spencer Wu

## Context

An authorization reserves funds without moving them. A card auth for $50 means
the payer can no longer spend that $50 elsewhere, but no money has reached the
merchant and may never reach them — the auth can be captured for less, voided, or
left to expire.

There are two ways to model that:

1. Post entries into a pending or reserved account at authorization time, and post
   reversing entries on void or expiry.
2. Keep holds in their own table, post no entries at all, and subtract active
   holds when computing what an account can spend.

The append-only rule from [ADR-0002](0002-postgresql-as-sole-source-of-truth.md)
appears to force option 1: if the ledger is append-only and corrections are
reversing entries, then a hold that goes away should be a reversing entry.

## Decision

Option 2. Holds live in a dedicated `holds` table, post no ledger entries, and are
**mutable** — status transitions happen in place, which is the opposite of how
`entries` behaves.

The distinction that resolves the apparent conflict: **an entry is a fact about
money that moved, and a hold is state about money that has not.** Facts are
immutable and get corrected by appending; state changes. Modelling holds as
entries would mean writing ledger rows for events that never happened — an expiry
would post a reversal of a movement that never occurred, and `entries` would stop
being a record of actual money movement.

Available balance therefore becomes a subtraction rather than a sum:

```
available = posted − sum(active holds)
```

and `AccountBalances` is the one place that is defined. Every debit path checks
`available`, never `posted`.

The state machine is pushed into the database:

- `ck_hold_resolution` keeps `status`, `captured_transaction_id` and `resolved_at`
  in agreement — an `ACTIVE` hold has neither, a `CAPTURED` hold has both, a
  `VOIDED` or `EXPIRED` hold has only `resolved_at`.
- The `holds_terminal_is_final` trigger raises on any update to a row that is not
  `ACTIVE`. A hold has exactly one edge out of `ACTIVE`, and it is one-way.

There is deliberately no `captured_amount_minor` column. The captured amount is
the sum of the capture transaction's entries, reachable through
`captured_transaction_id`; storing it would be a cached balance under another
name.

`payee_account_id` is fixed at authorization time, as a card auth names the
merchant up front. Capture therefore takes no account arguments at all — it
resolves the hold, so it cannot post into the wrong account.

## Consequences

Void and expiry need no compensating entries, because nothing ever moved. The
hold simply stops being counted.

The single one-way edge out of `ACTIVE` is what lets capture and void be
**compare-and-sets** — a single conditional `UPDATE ... WHERE id = ? AND status =
'ACTIVE'` — rather than a read-decide-write under `FOR UPDATE`. Postgres
serialises two writers on the row, so of concurrent capture and void attempts
exactly one sees rowcount 1. A running-total design would have needed the lock.
Double-capture and capture-after-void are impossible in the database, not merely
unwritten in the application.

The safety invariant that falls out is `posted >= sum(active holds)` for every
account at all times, because every debit path subtracts holds before deciding.
That is why capture performs no funds check: the money this hold reserved is
provably still there, and re-checking would re-derive a guarantee that
authorization already bought.

Costs, plainly:

- Available balance needs a second query against `holds` alongside the entries
  sum. Two aggregates instead of one.
- Because the table is mutable it is **not itself an audit trail**. A hold's
  history is only its terminal status plus `resolved_at`; the intermediate story
  is gone. The database-level state machine is the mitigation — illegal
  transitions are impossible rather than merely absent from the log — but it is a
  mitigation, not a substitute for history. If per-transition auditing is ever
  required, that is a separate append-only table, not a change to this one.

Rowcount 0 on a compare-and-set conflates several causes, so both capture and void
re-read the row to say which one applied. All are terminal for the caller, but
"already captured", "already voided" and "expired" are three different things to
tell a merchant.

See [ADR-0012](0012-expiry-is-a-predicate-not-a-job.md) for the expiry half of
this, which is where the sharp edge turned out to be.
