# ADR-0020: Exactly-once effects, not exactly-once delivery

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

Duplicates on the consumer side are guaranteed, not hypothetical, for two
independent reasons:

1. The relay republishes after a crash between the broker's acknowledgement and
   its own `published_at` commit (ADR-0019).
2. Kafka's consumer offsets commit to the **broker**, while the effect of
   consuming commits to **our database**. Two commits, no transaction spanning
   them. A crash after the effect and before the offset commit redelivers
   something already applied.

So the question is never "can an event arrive twice". It is "does the second
arrival change anything".

## Decision

An `inbox_events` table keyed on `event_id`. The claim and the effect commit in
**one local transaction**:

```
BEGIN
  INSERT INTO inbox_events (event_id, ...) ON CONFLICT DO NOTHING
  0 rows inserted → already processed; do nothing
  1 row  inserted → run the handlers
COMMIT
```

The event id is read from the Kafka **header**, before the body is parsed.

Records that can never be processed — no `event-id` header, or one that is not a
UUID — are classified non-retryable and dead-lettered on the **first** attempt.

## Consequences

What is promised is **exactly-once effects**, not exactly-once delivery. Nobody
can offer the latter across a network. The delivery still happens twice; it is
the second one that changes nothing.

Splitting the claim from the effect is broken in both directions, and it is worth
being able to say which way each fails:

- **effect first, then record** — a crash in between re-applies on redelivery.
  Double effect.
- **record first, then effect** — a crash in between marks an event processed
  that never was. The redelivery is ignored and the effect is lost **forever,
  silently**, which is worse.

A handler that throws rolls the claim back with it, so the event is genuinely
unprocessed and redelivery is *correct* rather than merely tolerated.

`ON CONFLICT DO NOTHING` rather than `SELECT` then `INSERT`: two threads handed
the same redelivered event would both find no row and both proceed. The unique
index serializes them — the check and the claim are one statement, with no gap to
lose the race in. Same shape as the Ledger's idempotency claim (ADR-0010), for
the same reason.

Reading identity from the header before the body matters for the poison case: a
payload the consumer cannot deserialize must still be *identifiable*, or a
redelivery of it cannot be recognised as the same message and the dead-letter
topic fills with what look like distinct failures.

Dead-lettering immediately rather than after retries is a deliberate trade.
Kafka delivers a partition in order, so retrying a record that can never succeed
blocks every later event behind it — one bad message becoming an outage for every
aggregate that hashes to that partition. **Parking is quarantine, not handling:**
the event's effect never happened, and reconciliation reports a non-empty DLT so
somebody looks.

The inbox doubles as Payments' only local record of what the Ledger did, since it
cannot read the Ledger's database. Events with no interested handler are still
stored, so reconciliation compares against a complete story rather than one with
pages missing.
