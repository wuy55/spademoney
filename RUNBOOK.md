# RUNBOOK

Operating SpadeMoney. Short on purpose — a runbook nobody finishes reading at 3am
is not a runbook.

The single most useful thing to know: **`GET /reconciliation` on either service
answers "are the books right", and it is designed to be believed.** It re-derives
every invariant from raw rows rather than asking the code that wrote them. Start
there.

---

## Normal operation

```bash
docker compose up -d      # builds, migrates, seeds
docker compose ps         # all four healthy; `seed` shows "exited (0)" — that is success
docker compose logs -f payments
```

| Service | Port | Health |
|---|---|---|
| ledger | 8080 | `GET /actuator/health` |
| payments | 8081 | `GET /actuator/health` |
| postgres | 5432 | `pg_isready -U spademoney -d spademoney` |
| redpanda | 19092 (host) | `rpk cluster health` |

**The `seed` container exiting is expected.** It is one-shot: it seeds and stops.

**Payments' health does not depend on the Ledger, on purpose.** If the Ledger is
down, Payments stays healthy and its sagas retry. A Payments health check that
went red because a dependency went red would take a recoverable situation and
page someone for it.

**The Ledger's health does not depend on the broker, also on purpose.** With
Redpanda down, money keeps moving and events accumulate in the outbox. That is
the outbox working, not failing.

---

## Is anything wrong?

```bash
curl -s localhost:8080/reconciliation | python3 -m json.tool
curl -s localhost:8081/reconciliation | python3 -m json.tool
```

Both always return `200` — the request succeeded, the checks ran. The verdict is
`healthy` in the body. A `500` here would mean reconciliation itself is broken,
which needs the opposite response from "reconciliation found something".

Both also run on a timer and log every check by name, including when clean. A job
that is silent while healthy is indistinguishable from one that has stopped.

### Ledger checks

| Check | Means | Severity |
|---|---|---|
| `GLOBAL_ZERO_SUM` | credits ≠ debits somewhere | **stop and investigate** |
| `EVERY_TRANSACTION_BALANCED` | a transaction does not net to zero | **stop and investigate** |
| `NO_NEGATIVE_WALLETS` | a user wallet went below zero | **stop and investigate** |
| `RESERVATIONS_COVERED` | holds reserve more than an account holds | **stop and investigate** |
| `NO_OVER_REFUNDS` | refunded beyond the original | **stop and investigate** |
| `NO_ORPHANED_HOLDS` | the expiry sweeper has stopped | operational, not money |
| `OUTBOX_DRAINING` | the relay is stuck or the broker is down | operational, not money |

The first five are money invariants. If one fails, something wrote rows outside
the service — stop writing and investigate before doing anything else.

The last two are **not** money bugs, and the distinction is deliberate. A lapsed
hold stops reserving funds the moment it lapses whether or not the sweeper ran;
an undrained outbox means consumers are behind, not that the ledger is wrong.

### Payments checks

| Check | Means |
|---|---|
| `NO_STUCK_SAGAS` | a saga has not advanced in a long time — is the driver running? |
| `NO_ESCALATED_SAGAS` | a compensation failed; **a human is required** |
| `FAILED_SAGAS_RELEASED_THEIR_LIMIT` | a declined payment is still holding a cap |
| `NO_DANGLING_STEPS` | a finished saga left a step `PENDING` — a driver bug |
| `COMPLETED_PAYMENTS_EXIST_IN_LEDGER` | Payments claims a transaction the Ledger denies |
| `FAILED_PAYMENTS_RELEASED_THEIR_HOLD` | a failed payment left funds reserved, or was captured anyway |

---

## Common situations

### A payment is stuck in `PENDING`

```bash
curl -s localhost:8081/payments/<paymentId> | python3 -m json.tool
```

The `steps` array shows each step, its attempt count and its last error — that is
usually the whole diagnosis. Then, in order of likelihood:

1. **Is the driver running at all?** If *every* payment is stuck and no step has
   any attempts recorded, the scheduler is not firing. This has happened
   (`@EnableScheduling` was once missing); `SchedulingIsWiredTest` now guards it.
   ```bash
   docker compose logs payments | grep -i "saga\|Scheduled"
   ```
2. **Is the Ledger reachable?** `lastError` will say so. The saga will recover on
   its own once it is back — no intervention needed.
3. **Has it exhausted its budget?** Forward steps get 8 attempts, compensations
   50. After that the saga goes terminal rather than hanging forever.

### A saga is `FAILED` with `COMPENSATION_FAILED`

**This is the one state that genuinely needs a person.** It means funds are
reserved in the Ledger that Payments tried and failed to release, or a hold it
meant to void turns out to have been captured.

```bash
docker compose exec -T postgres psql -U spademoney -d payments -c \
  "SELECT id, hold_id, failure_message FROM sagas WHERE failure_code='COMPENSATION_FAILED'"
```

Then check what the Ledger actually thinks of that hold:

```bash
curl -s localhost:8080/holds/<holdId> | python3 -m json.tool
```

- `ACTIVE` → the void never landed. Retry it by hand; it is idempotent:
  ```bash
  curl -X POST localhost:8080/holds/<holdId>/void \
       -H "Idempotency-Key: manual-void-<holdId>"
  ```
- `CAPTURED` → the money moved on a payment reported as failed. Do **not**
  auto-refund. This is a business decision; escalate it.
- `VOIDED` / `EXPIRED` → the goal was reached by another route. The saga is
  over-reporting; safe, but worth a look at why.

### The outbox is not draining

```bash
docker compose exec -T postgres psql -U spademoney -d ledger -c \
  "SELECT count(*), min(occurred_at) FROM outbox WHERE published_at IS NULL"
docker compose exec -T postgres psql -U spademoney -d ledger -c \
  "SELECT id, event_type, attempts, last_error FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 5"
```

The relay stops the batch at the first failure rather than skipping past it, so
the **oldest unpublished row is the blockage** — `last_error` on it is the reason.
Usually the broker is unreachable:

```bash
docker compose exec redpanda rpk cluster health
```

No money is at risk while this is happening. The Ledger keeps working; consumers
are just behind.

### Messages in the dead-letter topic

```bash
docker compose exec redpanda \
  rpk topic consume spademoney.ledger.events.DLT --num 10
```

A record lands here when it can never be processed — typically a missing
`event-id` header. It is quarantined, **not handled**: the partition kept moving,
but that event's effect never happened. Anything here needs a human to decide
what the event should have done.

### Recovering from a killed service

Nothing to do. Restart it.

```bash
docker compose start ledger        # or: docker compose up -d
```

Sagas resume on their next tick via the same code path as the happy case. This is
exactly what `./chaos/chaos-test.sh` exercises; if you want to confirm the system
recovers on your machine, run it.

---

## Useful queries

```bash
L() { docker compose exec -T postgres psql -U spademoney -d ledger   -tA -c "$1"; }
P() { docker compose exec -T postgres psql -U spademoney -d payments -tA -c "$1"; }

# global zero-sum — the headline invariant
L "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries"

# an account's balance, derived
L "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries WHERE account_id=2"

# saga outcomes
P "SELECT status, count(*) FROM sagas GROUP BY status"

# in-flight sagas, oldest first
P "SELECT id, status, updated_at FROM sagas WHERE status IN ('RUNNING','COMPENSATING') ORDER BY updated_at LIMIT 10"

# holds still reserving funds
L "SELECT count(*) FROM holds WHERE status='ACTIVE' AND expires_at > now()"
```

---

## Configuration worth knowing

| Property | Default | Why it matters |
|---|---|---|
| `spademoney.saga.interval` | 250ms | also the latency of a payment's first step |
| `spademoney.saga.batch-size` | 20 | with the interval, sets the throughput ceiling |
| `spademoney.saga.lease` | 30s | must exceed one step's worst case, or steps get double-driven |
| `spademoney.saga.max-attempts` | 8 | forward steps; "declined" is an acceptable answer |
| `spademoney.saga.compensation-max-attempts` | 50 | far higher; there is no acceptable alternative to releasing funds |
| `spademoney.saga.hold-expiry` | 10m | must outlast the whole retry schedule |
| `spring.http.clients.read-timeout` | 5s | without it, a killed Ledger hangs instead of failing |
| `spademoney.outbox.relay.interval` | 1s | how far behind consumers can be |

## Full reset

Destroys all data, including the demo seed:

```bash
docker compose down -v && docker compose up -d
```

Needed if you ever see `database "ledger" does not exist` — that means an old
data volume survived from before the two-database layout, and the Postgres init
scripts were skipped because the volume already existed.
