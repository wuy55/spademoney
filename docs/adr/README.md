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
| [0003](0003-orchestrated-saga-over-2pc.md) | Orchestrated saga over two-phase commit | Accepted |
| [0004](0004-transactional-outbox.md) | Transactional outbox for event publication | Accepted |
| [0005](0005-no-redis-idempotency-in-postgres.md) | No Redis; idempotency records live in Postgres | Accepted |
| [0006](0006-pessimistic-locking-ordered-acquisition.md) | Pessimistic locking with ordered acquisition | Accepted |
| [0007](0007-exactly-two-services.md) | Exactly two services | Accepted |
| [0008](0008-explicit-sql-over-jpa.md) | Explicit SQL over JPA/Hibernate | Accepted |
| [0009](0009-jqwik-pinned-to-1-9-x.md) | jqwik pinned to 1.9.x | Accepted |
| [0010](0010-idempotency-in-a-dedicated-table.md) | Idempotency in a dedicated table, not a column on transactions | Accepted |
| [0011](0011-holds-are-state-not-entries.md) | Holds are state, not ledger entries | Accepted |
| [0012](0012-expiry-is-a-predicate-not-a-job.md) | Expiry is a predicate, not a scheduled job | Accepted |
| [0013](0013-hand-written-openapi-not-springdoc.md) | Hand-written OpenAPI, not springdoc | Accepted |
| [0014](0014-no-negative-balances.md) | No negative balances; a refund may fail | Accepted |
| [0015](0015-parent-pom-not-shared-module.md) | One parent pom, still no shared module | Accepted |
| [0016](0016-two-databases-one-instance.md) | Two databases in one Postgres instance | Accepted |
| [0017](0017-callers-idempotency-key-never-forwarded.md) | The caller's idempotency key is never forwarded | Accepted |
| [0018](0018-502-and-504-are-different-answers.md) | 502 and 504 are different answers | Accepted |
| [0019](0019-outbox-written-in-the-domain-transaction.md) | The outbox is written in the domain transaction, never published from it | Accepted |
| [0020](0020-exactly-once-effects-not-delivery.md) | Exactly-once effects, not exactly-once delivery | Accepted |
| [0021](0021-deterministic-saga-step-keys.md) | Step idempotency keys are `saga:{sagaId}:{step}`, with persisted bodies | Accepted |
| [0022](0022-saga-driver-is-a-poller.md) | The saga driver is a poller with no synchronous start path | Accepted |
| [0023](0023-compensations-get-a-larger-retry-budget.md) | Compensations get a much larger retry budget than forward steps | Accepted |
| [0024](0024-reconciliation-re-derives.md) | Reconciliation re-derives, and duplicates constraints on purpose | Accepted |
| [0025](0025-test-config-layers-onto-shipped-config.md) | Test configuration layers onto shipped configuration, never replaces it | Accepted |
| [0026](0026-scheduled-jobs-are-asserted-not-assumed.md) | Background jobs are asserted to be scheduled, not assumed | Accepted |
| [0027](0027-cloud-agnostic-by-construction-deployed-nowhere-yet.md) | Cloud-agnostic by construction; deployed nowhere yet | Accepted |

Records 0003, 0004 and 0007 were written before the code existed; 0019 through
0024 are how those decisions actually turned out once built, and supersede the
earlier records in detail without contradicting them.

Records 0018, 0025 and 0026 each document a bug that shipped. They are kept in
the same series as the design decisions on purpose: what a system got wrong, and
how it was caught, is a design record too.

Record 0027, like 0003, 0004 and 0007, is binding but not yet built: the system
is built cloud-agnostic, but nothing is deployed to any cloud yet.
