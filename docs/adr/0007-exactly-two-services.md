# ADR-0007: Exactly two services

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** Spencer Wu

> **Implementation status:** one service exists today (`services/ledger`). The
> second is the seam this decision reserves; the point of the record is the
> upper bound, which is already binding.

## Context

The distributed-systems claims this project makes — a transactional outbox, an
orchestrated saga, recovery after a process is killed mid-workflow — are only
real if there is more than one process. A single service can simulate them, but
nothing about the simulation is falsifiable, because the failure modes that make
those patterns necessary cannot occur.

The opposite failure is more common. Splitting a system into many services makes
the architecture diagram look serious and makes every claim harder to verify: more
deployment surface, more configuration, more places for a reader to have to take
your word for something.

## Decision

Exactly two services, and no more. One ledger service owning accounts, entries,
holds and idempotency; one second service on the other side of a real network
boundary, which is what makes the outbox and saga claims testable.

No API gateway, no service mesh, no third service in another language. Deployment
is Docker Compose, not Kubernetes.

## Consequences

There is one deliberate seam, and it can be explained end to end: what crosses it,
what happens when it is down, and what state each side is left in. Every
distributed claim in this repository is demonstrable against that seam rather than
asserted about a hypothetical one.

The topology proves nothing about operating at large service counts. Service
discovery, mesh routing, and multi-team deployment coordination are genuinely
absent, and this record is not an argument that they do not matter — only that
adding them here would add explanation surface without adding proof.

The bound is the useful part. "Two" is a constraint that has to be argued against
before a third service can appear, which is the opposite of the usual default
where a new service is the path of least resistance.

Related: [ADR-0003](0003-orchestrated-saga-over-2pc.md) and
[ADR-0004](0004-transactional-outbox.md) are the claims this seam exists to make
testable.
