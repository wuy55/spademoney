# ADR-0004: Transactional outbox for event publication

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** Spencer Wu

> **Implementation status:** this decision is binding but not yet realised in
> code. There is no outbox table and no relay in this repository today; nothing
> currently publishes events. The record is written ahead of the implementation
> because it determines the shape of the schema change when it lands.

## Context

A service that changes state and then publishes an event about it is performing a
dual write to two systems that do not share a transaction. Both orderings fail:

- Commit first, then publish. A crash in between leaves state changed and no
  event. Downstream never learns, and the divergence is silent.
- Publish first, then commit. A rollback leaves an event describing something
  that did not happen, which is worse, because consumers act on it.

Neither is fixable with retries, because the failure is the absence of atomicity,
not a transient error.

## Decision

Events are written to an `outbox` table in the same database transaction as the
state change they describe. A separate relay reads unpublished rows, publishes
them to the broker, and marks them published.

The event and the state change therefore commit or roll back together, because
they are the same commit.

## Consequences

State and events cannot diverge. If the state changed, the event exists; if it
does not exist, the state did not change.

Publication is at-least-once, not exactly-once. The relay can publish a row and
crash before marking it, so the same event goes out twice. Consumers must
therefore deduplicate on the event id — this is a real obligation pushed onto
every consumer, and the honest framing is that the outbox buys consistency by
spending it on duplicate delivery.

There is a publication delay equal to the relay's poll interval, so consumers are
behind the database by that much. Events are ordered per aggregate rather than
globally, since the relay's ordering guarantee only extends as far as the key it
publishes on.

The outbox table grows monotonically and needs a retention policy. That is a
known follow-up, in the same category as the reaper for stranded idempotency
records noted in [ADR-0010](0010-idempotency-in-a-dedicated-table.md).

This pattern is what makes the sagas of [ADR-0003](0003-orchestrated-saga-over-2pc.md)
implementable without a distributed transaction: each saga step is a local commit
that atomically records both its own effect and the message that triggers the
next step.
