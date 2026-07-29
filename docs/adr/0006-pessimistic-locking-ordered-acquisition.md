# ADR-0006: Pessimistic locking with ordered acquisition

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** Spencer Wu

## Context

A transfer must read a balance and then post against it. If another transfer
posts in the gap between the read and the write, both can pass an overdraft check
that only one should have passed. That gap is where double-spend lives, and
closing it is the entire job.

Optimistic locking closes it by detecting the conflict at write time and retrying.
That works well when conflicts are rare, and badly on the hot accounts a payments
system actually has: a busy merchant account turns into a retry storm where
attempts fail, retry, and collide again, and throughput collapses exactly when it
matters.

Pessimistic locking closes it by taking the lock first. It introduces the other
classic failure instead: two transactions that lock the same two rows in opposite
orders deadlock, and a transfer from A to B concurrent with a transfer from B to A
is precisely that situation.

## Decision

Pessimistic row locks, acquired in a fixed order. `lockBothAscending` selects both
account rows `FOR UPDATE` in ascending `id` order, in a single statement, before
any balance is read:

```sql
SELECT id, currency FROM accounts WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE
```

Ascending id is the whole deadlock argument. Two transfers in opposite directions
request the same two locks in the same order, so no cycle can form. Every path
that locks two accounts uses this one method — transfer and refund both — so the
rule cannot be forgotten in a new caller without visibly not calling it.

The lock is taken before the balance is read, which is what closes the original
gap. The same statement also verifies both accounts exist and that their currency
matches the request, so those checks happen under the lock rather than before it.

Paths that lock a single account are exempt by construction. Authorization locks
only the payer, because an authorization does not constrain the payee's balance
and locking a hot merchant would serialise every authorization against it for no
correctness gain. A transaction holding one lock cannot be part of a cycle, so
this does not weaken the rule.

Capture and void take no account lock at all, because both can only *increase*
the payer's available balance. A concurrent transfer can therefore only have read
an available balance that is stale-low, never stale-high, so its overdraft
decision remains valid. See
[ADR-0011](0011-holds-are-state-not-entries.md) for why those paths are
compare-and-sets instead.

## Consequences

The transfer path is provably deadlock-free, not empirically so, and the argument
is one sentence long. A bidirectional-transfer test guards the rule against
regression.

Hot accounts serialise. Transfers touching the same account queue behind each
other, which is a real throughput ceiling and the accepted price of correctness at
this scale. Sharding by account is the answer if that ceiling is ever reached, and
it is deliberately out of scope here because it changes this argument.

Locks are held for the duration of the transaction, so anything slow inside a
transaction is now everyone's problem. In practice that means no network calls
between acquiring the lock and committing.

The rule is a convention enforced by a shared method rather than by the type
system. A future path that writes its own `FOR UPDATE` in a different order would
reintroduce deadlocks, which is why `lockBothAscending` is the only place that
statement appears.
