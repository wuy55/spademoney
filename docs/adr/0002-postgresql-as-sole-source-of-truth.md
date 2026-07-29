# ADR-0002: PostgreSQL as the sole source of truth

- **Status:** Accepted
- **Date:** 2026-07-15
- **Deciders:** Spencer Wu

## Context

A ledger needs three things that are hard to assemble out of parts: atomic
multi-row writes, real isolation between concurrent writers, and row-level locks
that a debit path can take before it reads a balance. Systems that offer
eventual consistency or per-document atomicity make double-entry bookkeeping a
distributed correctness problem rather than a database feature.

The related question is where a balance lives. A balance can be a stored column
updated on every posting, or it can be derived by summing entries. The stored
column is faster and is a cache, with all of a cache's failure modes: it can
drift from the entries that produced it, and once it has drifted there is no
principled way to decide which of the two is right.

## Decision

PostgreSQL is the only durable store, and it holds the invariants rather than
merely the data. Specifically, the schema enforces:

- Entries are append-only. The `entries_immutable` trigger raises on any UPDATE
  or DELETE, so a correction can only ever be a new reversing entry.
- Every transaction nets to zero per currency. `entries_balanced` is a
  `DEFERRABLE INITIALLY DEFERRED` constraint trigger, so it fires once at commit
  with the whole transaction visible, not mid-insert when only one side exists.
- Amounts are positive. Combined with the zero-sum rule, that forces at least two
  entries per transaction.

Balances are derived, never stored. `AccountBalances` is the single definition of
posted, held, and available, and each is a query over `entries` and `holds`.

## Consequences

The invariants hold against any writer, including a future service, a migration
script, or a person with a `psql` prompt. Application code cannot be the only
thing standing between the ledger and an unbalanced transaction, because
application code is exactly what tends to acquire a second, less careful caller.

Derived balances cannot drift, and reconciliation has nothing to reconcile
against. The price is that reading a balance is an aggregate over an account's
entries, which grows with history. At this scale that is a non-issue; the answer
when it stops being one is a periodic balance snapshot that is itself derived and
checkable against the entries, not a mutable running total.

Horizontal write scale is bounded by one Postgres primary. Sharding by account is
the eventual answer and is deliberately not attempted here, because a sharded
ledger changes the locking argument in [ADR-0006](0006-pessimistic-locking-ordered-acquisition.md)
and would be a much larger decision than it looks.

Choosing the database as the enforcement point is also what makes
[ADR-0008](0008-explicit-sql-over-jpa.md) follow: if the constraints live in SQL,
the queries that must cooperate with them should be visible SQL too.
