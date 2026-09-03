# ADR-0017: The caller's idempotency key is never forwarded

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** Spencer Wu

## Context

Payments receives an `Idempotency-Key` from its caller and must send one to the
Ledger. The tempting move is to forward the caller's key unchanged: it is already
unique, the client already generated it, and it seems to preserve the chain.

## Decision

**The caller's key is never forwarded.** Payments derives the key it sends —
`saga:{sagaId}:{step}` (ADR-0021).

## Consequences

An idempotency key names *one operation within one service's scope*. The caller's
key names "this `POST /payments`". The key sent to the Ledger names "this hold
authorization". Those are different operations, and forwarding one key for both
is wrong in two directions:

1. One key would name two different operations across two services, so a replay
   of the payment and a replay of a step become indistinguishable.
2. A payment is **three** Ledger calls. A single forwarded key would make the
   second collide with the first inside one Ledger scope — same key, different
   body, `422 IDEMPOTENCY_KEY_REUSED`. The saga would break on step two, every
   time.

The second point is the one that turns this from a modelling preference into a
requirement. It is not visible at all while a payment is a single call, which is
exactly why it was worth deciding before the saga existed rather than
discovering afterwards.

The cost is one more thing to get right: the derived key has to be
*deterministic*, or a retry becomes a second charge. Session 6 shipped a
derivation that was not — a fresh UUID per request — and carried it as a
documented double-charge window for three sessions rather than half-fixing it.
ADR-0021 closes it.
