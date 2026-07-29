# ADR-0003: Orchestrated saga over two-phase commit

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** Spencer Wu

> **Implementation status:** this decision is binding but not yet realised in
> code. No cross-service workflow exists in this repository today; every write
> path currently completes inside one local ACID transaction. The record exists
> because the choice constrains how the second service is built, and writing it
> down before the code is the point of having a decision log.

## Context

Once a workflow spans more than one service, "do all of it or none of it" stops
being free. The two standard answers are a distributed transaction coordinated by
two-phase commit, or a saga: a sequence of local transactions where each step has
an explicit compensating action.

Two-phase commit gives the stronger guarantee and charges for it in availability.
The coordinator is a synchronous participant in every write, locks are held
across the network for the duration of the protocol, and a coordinator failure
between prepare and commit leaves participants blocked holding those locks.

## Decision

Cross-service workflows are orchestrated sagas. Each step is a local ACID
transaction in one service, and each step that can be undone has a named
compensating step. An orchestrator holds the workflow state explicitly rather
than leaving it implicit in a chain of events, so at any moment there is one row
that says what stage a workflow is in and what it will do next.

Orchestration rather than choreography because the state a reader most wants —
what is in flight, what is stuck, what compensated — is a first-class thing to
query rather than something to reconstruct by replaying a topic.

## Consequences

Availability survives a participant being slow or down: the workflow parks in a
known state instead of holding locks open across services.

The guarantee weakens from atomic to eventually consistent, and that weakening is
visible to users. There is a window where one service has committed and the next
has not, so any read that spans the seam can observe a partial workflow. Naming
that window is a design obligation, not something to paper over.

Every step must be idempotent and retry-safe, because a saga's recovery
mechanism is re-running steps. This is not an extra requirement so much as the
same requirement [ADR-0010](0010-idempotency-in-a-dedicated-table.md) already
imposes at the API edge, applied one layer down.

Compensation is not rollback. A compensating step is a new business action with
its own ledger consequences — the same principle that makes a refund a reversing
transaction rather than an edit, per
[ADR-0011](0011-holds-are-state-not-entries.md) and the append-only rule in
[ADR-0002](0002-postgresql-as-sole-source-of-truth.md). Some steps have no
compensation and must therefore be ordered last.
