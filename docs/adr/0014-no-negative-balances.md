# ADR-0014: No negative balances; a refund may fail

- **Status:** Accepted
- **Date:** 2026-07-26
- **Deciders:** Spencer Wu

## Context

A refund debits whoever was originally paid. Nothing guarantees they still have
the money — a merchant who has already withdrawn their balance cannot cover a
refund of a sale from last week.

Real acquirers deal with this. They hold a reserve, roll a percentage of
settlement, or underwrite a negative balance and collect it later. All three are
ways of deciding **who carries the exposure** while the merchant is short.

For a ledger, the question is narrower: does the refund path get an exemption from
the overdraft check that every other debit obeys?

## Decision

No exemption. A refund passes through the **same available-balance check as any
other debit**. If the original payee is short, the refund fails with
`422 INSUFFICIENT_FUNDS`.

**No account may go negative, on any path.**

This record also fixes error precedence on the refund path. The refund cap — you
cannot refund more than the original — is checked **before** the funds check,
because an over-cap refund can never succeed however long the client waits, while
an empty balance can be topped up. Reporting the retryable error when a permanent
one also applies would tell the client to keep trying something that will never
work. Pinned by `whenBothTheCapAndTheBalanceRejectItTheCapIsReported`.

## Consequences

`balance >= 0` stays a provable, testable invariant across every path, and
reconciliation never has to distinguish "negative by policy" from "negative by
bug". That distinction is the expensive one — once some negatives are legitimate,
every negative needs a human to classify it.

The trade is that **refunds are not guaranteed to succeed**, which is unrealistic
for a production acquirer. A customer owed a refund does not care about the
merchant's balance, and a real payment processor cannot answer "your refund
failed, the merchant is out of money."

The honest framing is that the production answer is a platform reserve or an
underwritten negative balance, and the schema already supports either: the
`CLEARING` account type exists for exactly this, and a refund funded from a
reserve is a three-account posting that still nets to zero. Choosing between them
is a business decision about who carries the risk — not a limitation of the ledger
and not a schema change.

The refund cap is derived, never cached. "How much has been refunded so far" is a
sum over the entries of every `REFUND` transaction pointing at the original, per
[ADR-0002](0002-postgresql-as-sole-source-of-truth.md); a running total on the
original row could drift from the entries that are the actual source of truth.
Deriving it makes the cap check a read that must be serialised, which is why the
refund path takes the ordered locks of
[ADR-0006](0006-pessimistic-locking-ordered-acquisition.md) before reading it —
two concurrent refunds of the same transaction would otherwise both read the same
"already refunded" total and together exceed the original. `lockBothAscending`
therefore does double duty: deadlock rule, and serialiser for the cap.

Refunds of refunds are rejected outright, as are transactions whose posting is not
a simple two-entry pair. Both are cases where "run the original backwards" has no
single correct meaning, and guessing would be worse than refusing.
