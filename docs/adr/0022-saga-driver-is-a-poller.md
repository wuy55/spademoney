# ADR-0022: The saga driver is a poller with no synchronous start path

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

A saga has to be advanced by something. The obvious design runs the first step
synchronously inside the `POST /payments` request — it is faster, and the caller
gets a real answer — and adds a background poller to pick up whatever was left
behind by a crash.

That produces two code paths to the same outcome, and the second one only ever
runs when something has already gone wrong.

## Decision

**One path.** `POST /payments` writes the saga, commits, and returns `202
Accepted` with a `Location` header. A scheduled driver advances it, one step per
tick. There is no synchronous start.

The driver claims work with a **lease** — an `UPDATE` that pushes
`next_attempt_at` into the future and commits — rather than holding a transaction
open across the step. Retries use exponential backoff with **full jitter**.

## Consequences

**Recovery is not a separate code path.** A saga resumed after a crash is driven
by the same method, in the same order, as one that never failed. The recovery
logic is therefore exercised by every test in the suite rather than by the one
test that remembers to kill something. Systems where "resume" is its own routine
are systems where resume is the least-tested code in the building.

The lease means no network call ever holds a database lock. Holding a transaction
open across a call to a slow peer is how a peer's problem becomes a database
problem. A driver that dies mid-step leaves a lease that simply expires, and the
next tick picks the saga up.

One tick, one step — so a saga cannot monopolise the driver thread and a bug in
the plan cannot become an infinite loop inside a tick.

**Full jitter, not plain exponential.** Without jitter, everything that failed at
the same moment retries at the same moment: an outage *synchronises* the clients,
and the retry storm becomes the second outage. Full jitter — a uniform draw from
`[0, computed]` — spreads them widest.

The endpoint returns **202, not 201**. `201 Created` promises the resource exists
in its finished form, and a saga that has not run a step is not that. The
`Location` header was deliberately withheld for three sessions because
`/payments/{id}` would have pointed at a `404` — the Ledger had already shipped
exactly that lie for two sessions in M2 — so it arrives with the resource it
names and not earlier.

The costs, stated: one tick of latency on the first step (250 ms by default), and
a throughput ceiling of `batch-size / interval` steps per second. Both are
configuration rather than discovered limits, and the load report measures the
ceiling instead of pretending it is not there. The lease-based claim means
additional driver instances are safe in principle — but that is untested here,
so it is not claimed.
