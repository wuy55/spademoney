# ADR-0023: Compensations get a much larger retry budget than forward steps

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

The first implementation gave every step the same retry budget. That looked
symmetrical and was wrong, and the chaos test is what showed it: an outage long
enough to *decline* a payment was also long enough to fail that payment's
compensation. The system then escalated a dead end it would have cleared itself
given a few more seconds.

No unit test could have found this. It only appears when a real dependency is
gone for longer than a retry schedule.

## Decision

Different budgets, because the two cases have different acceptable outcomes:

| | Attempts | Then |
|---|---|---|
| Forward step | 8 | give up, compensate what succeeded |
| Compensation | 50 | escalate as `COMPENSATION_FAILED` |

Compensations also run in **reverse order** of the steps they undo, and only for
steps that actually **succeeded**.

## Consequences

**"Declined" is an acceptable answer to a forward step; there is no acceptable
alternative to releasing a customer's funds.** That asymmetry is the whole
argument. A payment retried forever is worse for everyone than a decline — no
charge, no decline, no answer — so the forward budget stays finite. A
compensation abandoned early leaves funds reserved behind a payment that already
failed, which nobody chose and no customer can act on.

Reverse order is not cosmetic. The cap is released before the hold is voided, so
there is never an instant where the cap is free but the funds are still reserved
— which is exactly the window a customer retrying immediately after a decline
would fall into, passing the cap check and then failing on funds their own
abandoned hold was holding.

Compensating only *succeeded* steps avoids issuing a void for a hold that was
never taken, which would turn a clean failure into a `404` and a stuck saga.

`CAPTURE` deliberately has **no compensator**. Undoing a capture means posting a
refund, and a refund is a business decision with its own authorization — not
something a retry loop should issue on its own initiative at 4am. Capture is
therefore the saga's point of no return: succeed and it is `COMPLETED`, fail and
everything before it is undone.

Two states are named rather than hidden. An exhausted compensation becomes
`COMPENSATION_FAILED`, reported by reconciliation as needing a human. And a void
over a hold that turns out to be `CAPTURED` escalates rather than reporting
success — because **a compensation is defined by its goal (the funds are not
reserved), not by its action**. An already-`EXPIRED` hold has reached that goal by
another route and counts as done; a `CAPTURED` one has not, and a saga quietly
reporting `COMPENSATED` over a real charge is the exact lie this milestone exists
to make impossible.

The residual risk is a compensation that fails for 50 attempts and then still
needs a person. That state is real, it is named, and reconciliation surfaces it.
A system that cannot admit it simply hides it.
