# ADR-0021: Step idempotency keys are `saga:{sagaId}:{step}`, with persisted bodies

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

ADR-0017 established that Payments derives the key it sends to the Ledger rather
than forwarding the caller's. It did not make the derivation *deterministic*: the
first implementation minted a fresh UUID per request, so a client retrying the
same `Idempotency-Key` produced a **second transfer**. That was carried
deliberately for three sessions as a documented double-charge window, on the
grounds that half-building the real fix would be a worse foundation than an
obviously missing one.

This is the real fix.

## Decision

Each step's key is `saga:{sagaId}:{step}`, where the saga id is allocated from
the caller's `Idempotency-Key` through a `UNIQUE` constraint and **persisted
before the first step runs**.

Each step's request body is written to `saga_steps.command` when the step is
first created and **resent verbatim** on every subsequent attempt. Step creation
uses `ON CONFLICT DO NOTHING` and the driver re-reads the row afterwards.

## Consequences

Both a client retry and a driver retry send the same key and the same body, so
each is a **replay** at the Ledger rather than a second effect.

**This is what dissolves the 504 of ADR-0018.** Nothing about the ambiguity is
resolved — Payments still cannot know whether a timed-out call landed, and no
amount of cleverness will tell it. What changed is that it stopped mattering,
because resending is safe. The correct response to "I don't know" turned out not
to be finding out; it was making the question harmless.

Note what was *not* done: no status-lookup call to ask the Ledger whether the
transfer exists. That races an in-flight commit and can confidently report "no
transfer" for one that is about to exist.

**Persisting the body is not caching.** The Ledger fingerprints request bodies
and answers `422 IDEMPOTENCY_KEY_REUSED` when a known key arrives with a
different one. A retry that rebuilt its body from saga state that had moved on in
the meantime would hash differently — and the saga would not fail, it would
**wedge**, permanently, in a way that looks like a client bug rather than an
outage. That failure is much harder to notice than a loud one, which is why the
column exists.

The `ON CONFLICT DO NOTHING` plus re-read is the same concern one level up: a
driver that crashed after creating a step but before running it comes back and
must find the **original** command, not a freshly built one. Overwriting there
would reintroduce exactly the problem the column prevents.

The cost is a row per step and a JSONB body per row. Cheap, and it doubles as the
audit trail of what the saga believed it was doing at each point.
