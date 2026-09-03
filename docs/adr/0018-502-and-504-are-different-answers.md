# ADR-0018: 502 and 504 are different answers

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** Spencer Wu

## Context

When a call from Payments to the Ledger fails, there are two very different
situations hiding behind "the call failed":

- **The request never arrived.** Connection refused, unroutable, timed out
  dialling. The money certainly did not move.
- **The request arrived and the answer was lost.** Read timeout. The money may or
  may not have moved, and Payments cannot tell.

The easy implementation collapses both into one error. That erases the only
distinction a caller can act on.

## Decision

They are different answers with different status codes:

| Situation | Exception | HTTP | Retry safe? |
|---|---|---|---|
| 5xx, refused, unreachable | `LedgerUnavailableException` | `502` | **yes** — not processed |
| read timeout | `LedgerTimeoutException` | `504` | **no** — outcome unknown |

A `2xx` whose body cannot be parsed is treated as *unavailable*, not success. We
do not invent a transaction id.

## Consequences

The ambiguity is visible in the API rather than hidden behind a guess. A caller
that receives `502` may resubmit safely; one that receives `504` may not.

**The ordering of the cause-chain checks is load-bearing, and I got it wrong
first.** `java.net.http.HttpConnectTimeoutException` *extends*
`HttpTimeoutException`, so a check written in the obvious order — general case
first — silently classifies every unreachable Ledger as ambiguous. That is the
worst available direction: it makes the safe case unretryable and, worse, drains
`504` of meaning, when `504` is the exact ambiguity the rest of the system exists
to resolve.

Every unit test stayed green, because `MockRestServiceServer` was only ever asked
to throw `SocketTimeoutException` — **the mock could only test the failure I had
already thought of.** It surfaced against a stopped container, by noticing the
response came back after 2036 ms: the *connect* timeout, not the 5s read timeout.
The service was claiming not to know something it provably knew.

Fixed by testing the connect case first, with two regression tests
(`HttpConnectTimeoutException` and `ConnectException`) pinning the order.

This decision survives ADR-0021, but its meaning narrows. Once step keys are
deterministic, the *saga* can treat both as retryable, because resending is a
replay either way. The two codes still differ at the API boundary, because a
caller outside the saga still needs to know which happened.
