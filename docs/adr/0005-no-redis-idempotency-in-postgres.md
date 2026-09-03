# ADR-0005: No Redis; idempotency records live in Postgres

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** Spencer Wu

## Context

The reflexive place to put idempotency keys is Redis: the access pattern is a
keyed lookup, the records are short-lived, and `SET NX` looks like exactly the
primitive the job needs.

It is the wrong home, for a reason that has nothing to do with speed. An
idempotency record's whole purpose is to answer "did this money movement already
happen?". If the record lives in one system and the money movement lives in
another, answering that question correctly requires both to commit together — and
they cannot, because they are two systems. A crash between the two leaves either
a key claimed for a transfer that never posted, which permanently blocks a
legitimate retry, or a transfer posted with no key, which lets a retry post it
twice.

That is the dual-write problem from [ADR-0004](0004-transactional-outbox.md),
reintroduced by a cache chosen to make the guard faster.

## Decision

No Redis. Idempotency records live in the `idempotency_keys` table in the same
PostgreSQL database as the ledger, and `IdempotencyService.execute` performs the
claim, the state change, and the completion write inside one transaction.

## Consequences

The claim and the money movement commit or roll back together, so the record
means what it says. A crash mid-request rolls back the claim along with
everything else, which frees the key — a claimed key names an operation that
succeeded, never an attempt that failed. Nothing can strand an `IN_PROGRESS` row
through ordinary failure; the 409 path exists for rows stuck out of band, not for
normal concurrency.

The serialisation point becomes the primary key on `(endpoint, idempotency_key)`,
not the status flag. Under `READ COMMITTED` a concurrent duplicate cannot see the
uncommitted `IN_PROGRESS` row at all — it blocks on the unique index until the
first transaction commits, then re-reads and replays the stored response. The
index does the work that a distributed lock would otherwise have to.

One fewer moving part to run, monitor, and explain. The cost is that every
idempotency check is a database round trip rather than a cache hit, and those
round trips land on the same primary that is doing the ledger writes. At this
scale that is not a bottleneck. If it becomes one, the right response is a
read-side cache in front of derived data, with reconciliation proving cached
equals derived — not moving the source of truth out of the transaction.

**2026-09-04 addendum:** this last paragraph is the decision that governs any
future caching work, not just a closing remark — see "What I'd do next" in
`README.md` and `DESIGN.md` §13, where a Redis read-through cache for balance
lookups and payment-status polling is scoped against exactly this line. Nothing
about the boundary changed; this note exists so a reader who reaches this ADR
first knows the scope was decided here, before it was ever proposed as a
concrete piece of work.
