# ADR-0016: Two databases in one Postgres instance

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** Spencer Wu

## Context

Two services need separate data. Postgres offers three ways to arrange that:

| Option | Isolation | Can one transaction span them? |
|---|---|---|
| Two schemas, one database | naming only | **yes**, trivially |
| Two databases, one instance | real | no |
| Two instances | real + separate failure domain | no |

The whole point of the split is to make a distributed-consistency problem real
enough to have to solve. An arrangement that merely *discourages* a distributed
transaction does not do that.

## Decision

Databases `ledger` and `payments` in the same instance. Separate Flyway
locations, separate schema-history tables, no cross-database reads.

## Consequences

**Postgres has no cross-database query and no cross-database transaction**, short
of `PREPARE TRANSACTION` (two-phase commit, rejected in ADR-0003). So "just wrap
both writes in one transaction" is not discouraged here — it is *unavailable*.
The impossibility is enforced by the engine rather than by discipline.

That is the load-bearing reason, not tidiness. Two schemas would look like
separation and provide none: any developer, including me at 11pm, could write one
`BEGIN`, touch both, `COMMIT`, and get atomicity. The seam would be a convention.
Conventions are kept until the first deadline.

It also means referential integrity across the boundary is gone.
`payment_limits.account_id` names accounts that live in the Ledger's database
where no constraint can reach it, so it is deliberately not a foreign key. What
replaces it is periodic verification after the fact — the two cross-boundary
reconciliation checks (ADR-0024) exist precisely to pay this bill.

**The honest weakness:** one container means the two databases share a failure
domain, which a real deployment would not have. That is a laptop concession. It
does not weaken the chaos test, which kills *application* containers rather than
the database, and moving to separate hosts would change nothing about the code.

Asserted, not assumed: `to_regclass('public.accounts')` is `NULL` from Payments
and `to_regclass('public.payment_limits')` is `NULL` from the Ledger, and tests
fail if either stops being true.
