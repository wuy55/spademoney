# ADR-0008: Explicit SQL over JPA/Hibernate

- **Status:** Accepted
- **Date:** 2026-07-15
- **Deciders:** Spencer Wu

## Context

A Spring service reaching for persistence normally reaches for Spring Data JPA.
For most domains that is the right call: it removes a large amount of mapping
boilerplate and the generated SQL is nobody's concern.

A ledger is not most domains. Its correctness depends on properties of the exact
SQL that an ORM is specifically designed to hide:

- *Which* rows are locked, and *in what order*. The deadlock-freedom argument in
  [ADR-0006](0006-pessimistic-locking-ordered-acquisition.md) is a claim about one
  `SELECT ... ORDER BY id ASC FOR UPDATE`. An ORM decides when to flush and in
  what order it emits statements, which is exactly the decision that argument
  cannot delegate.
- *When* a statement runs relative to a commit. The `entries_balanced` constraint
  trigger is `DEFERRABLE INITIALLY DEFERRED` and both sides of a posting are
  inserted in one multi-row statement, so the trigger sees a complete transaction
  rather than firing on a one-sided insert.
- Immutability. Entries are append-only and enforced by a trigger. An ORM's
  natural idiom is a managed entity whose changes are flushed automatically, which
  is the one thing the `entries` table forbids.

## Decision

Spring's `JdbcClient` with hand-written SQL. No Hibernate, no JPA, no entity
manager. Rows map to Java records through explicit row mappers.

## Consequences

Every statement the ledger issues is visible in the source and reviewable in a
diff. There is no flush-order question, no lazy-loading surprise, no
first-level-cache aliasing, and no dependence on the ORM's dialect translation
for the constructs that matter.

The database is the enforcement point, per
[ADR-0002](0002-postgresql-as-sole-source-of-truth.md), and this decision keeps
the application layer honest about that rather than mediating it.

The cost is real boilerplate. Every query needs a row mapper, every result type
needs a record, and there is no derived-query magic. Refactoring a column means
finding the SQL by hand rather than letting a compiler find it, which is the
genuine safety property an ORM offers and this decision gives up.

`JdbcClient` returns Spring's `DataAccessException` hierarchy, so error handling
is at the SQL-state level rather than mapped into domain exceptions
automatically; those translations are written explicitly in the services.

The same reasoning produces [ADR-0013](0013-hand-written-openapi-not-springdoc.md):
where an artifact is the thing under review, it should be designed rather than
generated as a by-product.
