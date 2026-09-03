# ADR-0019: The outbox is written in the domain transaction, never published from it

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

ADR-0004 committed to the transactional outbox in principle. This is how it is
actually built, and why each part is the way it is.

The problem it solves has no safe ordering. "Write to the database, then publish
to the broker" — crash between them and the money moved but nobody was told.
"Publish first, then write" — the broker now knows about a transfer that rolled
back. There is no third ordering, because they are two systems with two commits.

## Decision

The event is inserted into an `outbox` table **inside the caller's existing
transaction**, the same one that inserted the entries. A separate relay publishes
committed rows afterwards.

`OutboxWriter` is deliberately **not** `@Transactional`. That absence is the
entire mechanism: it runs inside whatever transaction the caller already opened,
so the event and the money share a fate. Annotating it `REQUIRES_NEW` would
restore precisely the failure it exists to prevent.

`event_id` is a **column default** (`gen_random_uuid()`), minted by the insert.

The relay is single-threaded, reads `ORDER BY id ASC`, marks `published_at` only
after the broker acknowledges, and **stops the batch on the first failure**.

## Consequences

The transfer commits and the event is there to publish, or the transfer rolls
back and the event never existed. Two outcomes, no window between them.

Making `event_id` a column default means the relay has no code path that *could*
mint one. This matters more than it looks: a relay that generated ids would emit
a fresh one every time it republished after a crash, and every downstream dedupe
would silently match nothing. The invariant is "one event, one id, forever", and
it is enforced by the schema rather than by every call site remembering.

Delivery is **at-least-once and never at-most-once**. Marking published before
sending would trade a visible duplicate for a silent loss, which is a bad trade
when the thing lost is a statement about money. Duplicates are therefore expected
and are the consumer's problem to absorb (ADR-0020).

Stopping the batch on failure rather than skipping past it is deliberate.
Skipping is the intuitive choice and it quietly converts an ordered stream into
an unordered one at exactly the moment the system is already unhealthy.
Head-of-line blocking is the better failure: a stuck relay is loud, shows up as a
backlog, and is reported by reconciliation. A reordered stream is none of those.

This does not make Kafka transactional — nothing can. It removes the broker from
the write path, leaving one local transaction plus an at-least-once delivery
problem, which is a problem a consumer can actually solve.

The test that carries the whole argument is the one for a **refused** transfer:
it asserts *no event at all*. A publish-after-commit implementation passes every
happy-path test ever written and fails only that one.
