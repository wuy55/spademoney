# Architecture Decision Records

Each record states the context, the decision, and the consequences accepted,
including the unwelcome ones. Records are immutable once accepted; a changed
decision gets a new record that supersedes the old one.

Dates are when the decision was taken, not when it was written up. Records 0001
through 0010 are retroactive write-ups of decisions already visible in the commit
history.

| # | Decision | Status |
|---|---|---|
| [0001](0001-money-as-integer-minor-units.md) | Money as integer minor units | Accepted |
| [0002](0002-postgresql-as-sole-source-of-truth.md) | PostgreSQL as the sole source of truth | Accepted |
| [0003](0003-orchestrated-saga-over-2pc.md) | Orchestrated saga over 2PC | Accepted |
| [0004](0004-transactional-outbox.md) | Transactional outbox | Accepted |
| [0005](0005-no-redis-idempotency-in-postgres.md) | No Redis; idempotency records in Postgres | Accepted |
| [0006](0006-pessimistic-locking-ordered-acquisition.md) | Pessimistic locking with ordered acquisition | Accepted |
| [0007](0007-exactly-two-services.md) | Exactly two services | Accepted |
| [0008](0008-explicit-sql-over-jpa.md) | Explicit SQL (JdbcClient) over JPA/Hibernate | Accepted |
| [0009](0009-jqwik-pinned-to-1-9-x.md) | jqwik pinned to 1.9.x | Accepted |
| [0010](0010-idempotency-in-a-dedicated-table.md) | Idempotency in a dedicated table | Accepted |
| [0011](0011-holds-are-state-not-entries.md) | Holds are state, not ledger entries | Accepted |
| [0012](0012-expiry-is-a-predicate-not-a-job.md) | Expiry is a predicate, not a scheduled job | Accepted |
| [0013](0013-hand-written-openapi-not-springdoc.md) | Hand-written OpenAPI, not springdoc | Accepted |
| [0014](0014-no-negative-balances.md) | No negative balances; a refund may fail | Accepted |

Records 0003, 0004 and 0007 describe decisions that are binding but not yet
implemented in this repository; each says so at the top rather than leaving a
reader to infer it from the absence of code.
