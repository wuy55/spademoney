# SpadeMoney

A distributed payments ledger, built to be defended rather than demoed.

Double-entry money in PostgreSQL, split across two services that share no
database and therefore cannot share a transaction. Payments are driven by an
orchestrated saga with compensation. Kill the ledger mid-payment and it recovers
on its own — and two independent reconciliation jobs will tell you whether it
really did.

![A payment surviving the ledger being killed mid-transfer](docs/demo.gif)

That clip is `./chaos/demo.sh`. It sends one payment, `docker kill`s the Ledger
while the saga is in flight, restarts it, and shows the payment finish with no
manual intervention — then both services reconcile clean. Nothing in it is
staged; it is the same script anyone can run.

---

## What this is

| | |
|---|---|
| **Java 25, Spring Boot 4** | virtual threads, Jackson 3, `JdbcClient` with hand-written SQL |
| **PostgreSQL 16** | two databases, one per service — no cross-database transaction is possible |
| **Redpanda** | Kafka-compatible; the outbox relay's transport |
| **204 tests** | Testcontainers against real Postgres and a real broker |

Two services, exactly:

- **Ledger** — owns money. Accounts, immutable entries, authorization holds,
  capture, void, refunds-as-reversals, and the four-case idempotency contract.
- **Payments** — owns the workflow. Accepts a payment, drives a three-step saga
  across the boundary, compensates when a step fails.

---

## Architecture

```mermaid
flowchart LR
    client([client])

    subgraph payments["Payments · owns the workflow"]
        api["POST /payments<br/>202 + Location"]
        saga["saga driver<br/>poller · leases · backoff+jitter"]
        inbox[("inbox_events<br/>dedupe by event_id")]
        limits[("sagas · saga_steps<br/>payment_limits")]
    end

    subgraph ledger["Ledger · owns money"]
        rest["POST /holds<br/>/capture · /void"]
        money[("accounts · entries<br/>holds · idempotency_keys")]
        outbox[("outbox")]
        relay["relay<br/>single-threaded, id-ordered"]
    end

    broker{{"Redpanda<br/>spademoney.ledger.events"}}

    client -->|"1 · HTTP"| api
    api --> limits
    saga -->|"2 · commands, HTTP<br/>Idempotency-Key: saga:{id}:{step}"| rest
    rest --> money
    rest -.->|"same transaction"| outbox
    relay --> broker
    outbox --> relay
    broker -->|"3 · facts"| inbox
    inbox --> saga

    style ledger fill:#0d1117,stroke:#30363d,color:#c9d1d9
    style payments fill:#0d1117,stroke:#30363d,color:#c9d1d9
```

**Commands go one way over HTTP; facts come back the other way over Kafka.**
The asymmetry is the design. A command can be refused and the caller needs the
refusal synchronously. A fact has already happened and nobody may refuse it — it
only has to arrive eventually, and exactly once in effect.

---

## Quickstart

Requires Docker, `curl`, and `python3`. Nothing else — not even `jq`.

```bash
git clone https://github.com/wuy55/spademoney && cd spademoney
docker compose up -d          # builds both services, migrates, seeds demo accounts
```

Wait for all four services to report healthy (`docker compose ps`), then pay:

```bash
curl -i -X POST localhost:8081/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-first-payment' \
  -d '{"payerAccountId":2,"payeeAccountId":3,"amountMinor":2500,"currency":"USD"}'
```

You get `202 Accepted` and a `Location`. The payment is a saga that has been
written down, not one that has finished — follow it:

```bash
curl -s localhost:8081/payments/<paymentId> | python3 -m json.tool
curl -s localhost:8080/accounts/2/balance   | python3 -m json.tool
```

And ask both services whether the books are right:

```bash
curl -s localhost:8080/reconciliation | python3 -m json.tool
curl -s localhost:8081/reconciliation | python3 -m json.tool
```

### See the compensation path

Drop the payer's spending cap below the payment amount, then pay. The saga takes
the hold, the local limit check refuses, and it unwinds — releasing the cap and
voiding the hold in the Ledger:

```bash
curl -X PUT localhost:8081/limits/2 -H 'Content-Type: application/json' \
     -d '{"capMinor":1000,"currency":"USD"}'
```

---

## Proving it

Every number below comes from a committed script. None was typed by hand.

```bash
./chaos/smoke-test.sh     # happy path, end to end, over the real stack
./chaos/chaos-test.sh     # SIGKILL the ledger mid-saga; assert nothing was lost
./chaos/demo.sh           # the narrated one-payment version (the clip above)
./load/run.sh             # k6 load test; regenerates load/report.md
```

**Chaos** ([`chaos/`](chaos/)) — `docker kill`, not `stop`: SIGKILL, no graceful
shutdown. Payments are fired concurrently first so the kill lands across all
three saga steps at once, and the script verifies it actually landed mid-saga
rather than quietly asserting something weaker.

| Run | at the kill | outcome | ledger net |
|---|---|---|---|
| 8s outage | 12 in flight, 11 holds, 2 captured | 12 completed | **0** |
| 75s outage | 10 in flight, 11 holds, 2 captured | 8 completed, 4 compensated | **0** |

The 75-second run is the more interesting one: four payments outlived the retry
budget, were declined, and had their holds voided and caps released once the
Ledger returned.

What the chaos test asserts — read from the `entries` table directly, *then*
cross-checked against both services' reconciliation as a second opinion, because
a check that asks the system whether the system is correct is not a check:

- payee credited exactly `amount × completed`, payer debited the same
- the ledger nets to zero
- **exactly one `CAPTURE` per completed payment** — the line that would fail if a
  retry had ever become a second charge
- no hold left reserving funds, no spending cap left held

**Load** ([`load/report.md`](load/report.md)) — the accept path is not the
constraint; it is a single insert, and it stayed flat from 50 to 200 req/s with
a sub-10ms p99 and no errors. The saga driver's configured throughput is the real
ceiling, and the report says so rather than quoting the flattering number alone.

---

## Design documents

- **[DESIGN.md](DESIGN.md)** — the whole argument: what the invariants are, where
  the distributed problem actually is, and how each mechanism earns its place.
- **[docs/adr/](docs/adr/)** — 26 architecture decision records, including the
  decisions *not* taken and what they cost.
- **[RUNBOOK.md](RUNBOOK.md)** — operating it: health, stuck sagas, the
  dead-letter topic, what to do when reconciliation reports something.
- **[docs/openapi.yaml](docs/openapi.yaml)** — hand-written, kept honest by HTTP
  contract tests rather than generated from annotations.

---

## Known limitations

Stated here rather than discovered by a reader:

- **Both databases live in one Postgres container.** They share a failure domain,
  which a real deployment would not. It does not weaken the chaos test, which
  kills *application* containers.
- **A capture has no compensation.** Undoing one means posting a refund, which is
  a business decision with its own authorization — not something a retry loop
  should issue on its own.
- **The spending cap is a lifetime total, not a rolling window.** One `WHERE`
  predicate away; the interesting property is the check-and-record atomicity, not
  the calendar.
- **Cross-service reconciliation samples recent sagas** rather than all history.
  An unbounded diagnostic is an outage waiting for a busy day.
- **`/limits` has no authentication.** It is an operator surface, and a token
  nobody checks would be worse than saying so.
- **One saga driver instance.** The lease-based claim makes more of them safe,
  but that is untested and therefore unclaimed.

## License

MIT — see [LICENSE](LICENSE).
