# ADR-0010: Idempotency in a dedicated table, not a column on transactions

- **Status:** Accepted
- **Date:** 2026-07-24
- **Deciders:** Spencer Wu

## Context

A client retrying `POST /transfers` after a timeout must not move the money twice.
The client sends an `Idempotency-Key` header; the server has to decide what to do
with it.

The cheap implementation is a nullable `idempotency_key` column on `transactions`
with a unique index. It works for transfers and then stops working, for three
reasons:

1. It only covers endpoints that create a transaction. Authorize and void create
   holds, not transactions, and have nothing to hang the key on.
2. It stores the key but not the response. Replaying a duplicate then means
   re-deriving what the original reply was, which is a second implementation of
   the response that can disagree with the first.
3. It cannot tell "same key, same request" from "same key, different request".
   The second is a client bug and deserves an error, not a replayed response for
   an operation the caller did not ask for.

It also puts an HTTP concern in the ledger's domain table, where it will be read
by people reasoning about money.

## Decision

A dedicated `idempotency_keys` table, keyed `(endpoint, idempotency_key)`, holding
the request fingerprint, a status, the stored response status and body, and a
nullable FK to the transaction if the operation produced one.

`IdempotencyService.execute` is generic over the operation and implements a
four-case contract:

| Case | Response |
|---|---|
| No record | Claim the key, run the action, store the response |
| Record exists, fingerprint differs | `422` — key reused for a different request |
| Record exists, `IN_PROGRESS` | `409` with `Retry-After` |
| Record exists, `COMPLETED` | Replay the stored response verbatim |

The fingerprint is SHA-256 over a canonical form the request defines itself, so
"same logical request" is a property of the request type rather than of JSON
formatting. Fingerprint is checked *before* status: a key reused for a different
request is worth reporting even while the original is still in flight.

`endpoint` is a fixed string per endpoint and never contains a resource id. Ids
belong in the fingerprint — if they leaked into the scope, one key reused against
a different hold would quietly get its own key space instead of being rejected.

## Consequences

One mechanism serves every money-mutating endpoint: transfer, authorize, capture,
void, refund. Operations that create no transaction leave `transaction_id` null,
which is why the action returns an `Outcome` carrying the transaction id
separately from the response body.

Replay is verbatim, byte for byte, because the response body is stored rather
than recomputed. A retry cannot observe a different answer than the original
caller did.

Per-endpoint scoping means the same key value is independent across endpoints,
which is what a client naturally expects when it generates one key per logical
operation.

HTTP concerns stay out of `transactions`. The cost is an extra table and a join
when tracing a request to the money it moved.

The FK to `transactions` makes that trace possible but also means idempotency rows
outlive nothing on their own: there is no TTL and no reaper yet, so completed rows
accumulate indefinitely. That is a known gap, not a solved problem, and it is the
same category of follow-up as outbox retention in
[ADR-0004](0004-transactional-outbox.md).

The atomicity this rests on comes from
[ADR-0005](0005-no-redis-idempotency-in-postgres.md): the claim, the action and
the completion all commit together. The action must therefore not open its own
transaction — a self-invocation that bypasses the Spring proxy silently runs the
whole path on autocommit and breaks all four cases at once, which is why
`executeTransfer` carries its own `@Transactional` rather than relying on the
annotation on `execute`.
