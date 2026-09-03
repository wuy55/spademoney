# DESIGN

The argument behind SpadeMoney, in the order I would make it at a whiteboard.

The short version: **money is an integer in an append-only table; correctness
under concurrency comes from ordered pessimistic locks; correctness across a
service boundary comes from an outbox, a dedupe key and a saga; and the way you
know any of it worked is that something independent re-derives it and says so.**

---

## 1. Money

Integer minor units in a `long`, plus an ISO-4217 currency code. `Money` refuses
float construction, zero, negatives, and cross-currency arithmetic at the
boundary, so those cannot be represented rather than merely being discouraged.

Floats are excluded for the obvious reason — `0.1 + 0.2` — but the more useful
framing is that a float is a *lossy* type, and the loss is silent and
accumulating. An integer count of the smallest unit is exact and its overflow is
loud (`Math.addExact`).

## 2. Double-entry, and why the balance is not a column

There is no `balance` column anywhere. A balance is `SUM(credits) - SUM(debits)`
over an immutable `entries` table, derived every time it is asked for.

A cached balance is a second source of truth for the same fact, and the moment
two sources exist, the interesting question stops being "what is the balance" and
becomes "which one is right". Deriving it means the entries *are* the balance;
there is nothing to reconcile against them.

Every transaction posts at least two entries summing to exactly zero per
currency, and that is enforced **in the database** by a deferred constraint
trigger — deferred so it fires once at commit with the whole transaction visible,
rather than mid-insert when only one side exists. Amounts are always positive;
direction carries the sign. Positive-only amounts plus a net of zero forces at
least two entries, so a one-sided posting cannot be expressed.

Entries are append-only, enforced by a trigger that refuses `UPDATE` and
`DELETE`. A correction is a new reversing transaction, never an edit. This is
what makes the ledger an audit trail rather than a mutable table that happens to
hold money.

**`CASH` goes negative and that is correct.** Funding a wallet credits the wallet
and debits `CASH`, so the system's side of the books carries the negative of
everything ever funded. The invariant that matters is about customer money: no
`USER_WALLET` may go below zero. Getting that scope wrong is not hypothetical —
the reconciliation check for it initially had no `WHERE` clause and fired on every
healthy ledger (ADR-0024).

## 3. Concurrency: the exact race, and the exact fix

The race is read-then-write. Two concurrent transfers from the same account both
read a balance of 100, both decide that 80 is affordable, and both post. The
account ends at -60 and no single line of code was wrong.

The fix is to take the lock **before** the read: `SELECT ... FOR UPDATE` on the
account rows, then read the balance, then decide, then post — all in one
transaction. The overdraft check is atomic with the debit because nothing can
change the answer between checking and acting on it.

Locks are acquired in **ascending account-id order**, always. Two transfers in
opposite directions between the same pair of accounts request the same two locks
in the same order, so no cycle can form and deadlock is impossible by
construction rather than by retry. A bidirectional-transfer test guards the rule.

Pessimistic rather than optimistic: under contention on a hot account, optimistic
concurrency degrades into a retry storm, and the retries are most expensive
exactly when the account is busiest. Serializing a hot account is the accepted
cost; sharding it is the documented answer and is out of scope (ADR-0006).

What this costs, stated plainly: throughput per account is serial. The load
report measures across ten payers for that reason, and says so.

## 4. Idempotency, all four cases

Every money-mutating endpoint takes an `Idempotency-Key`. The contract:

| Case | Response |
|---|---|
| New key | perform it, store the response |
| Known key, same request fingerprint | replay the stored response verbatim; move no money |
| Known key, **different** fingerprint | `422 IDEMPOTENCY_KEY_REUSED` |
| Concurrent duplicate | one wins the unique index; the other blocks, re-reads, replays |

Three things make it work:

**The claim, the money and the stored response commit in one transaction.** So a
crash cannot strand an `IN_PROGRESS` row — the whole thing rolls back and the key
is free. A failed request frees its key, because the key names an operation that
*succeeded*, not an attempt that was made.

**`ON CONFLICT DO NOTHING` is the claim, not a `SELECT` first.** Two concurrent
duplicates would both find nothing and both proceed. The unique index serializes
them; the check and the claim are one statement with no gap to lose the race in.

**Fingerprint is checked before status.** A key reused for a genuinely different
request is a client bug worth reporting even while the original is still in
flight, and treating it as a replay would return the first payment's answer for a
different second payment.

## 5. Payment semantics: holds are state, not entries

An authorization reserves funds that have not moved. Modelling it as ledger
entries would mean writing rows for events that never happened — and an expiry
would then post a reversal of a movement that never occurred.

So a hold lives in its own table, posts nothing, and is **mutable**. The
resolution of the apparent conflict with "the ledger is append-only" is that an
**entry is a fact about money that moved** — facts do not change — while a **hold
is state about money that has not**.

`available = posted − sum(active, unexpired holds)`, and *every* debit path checks
available rather than posted. That single substitution is what makes capture safe
to post unconditionally later: the funds were already reserved.

**Expiry is a predicate, not a job.** Every reader filters `expires_at > now()`,
so a lapsed hold stops reserving funds the instant it lapses, whether or not the
sweeper has run. A paused sweeper therefore cannot become a money bug. The
corollary is the load-bearing part: since a lapsed hold still *reads* `ACTIVE`
until swept, capture's compare-and-set must carry `expires_at > now()` too —
without it, a late capture posts entries against funds already spent elsewhere
and mints money (ADR-0012).

---

## 6. The split: where the distributed problem actually is

Two services, two databases, in one Postgres instance. **Two databases rather
than two schemas, specifically because Postgres offers no cross-database
transaction.** Two schemas would look like separation and provide none — anyone
could open one `BEGIN`, touch both, and get atomicity. The impossibility is
enforced by the engine rather than by discipline.

This is the whole point of the milestone. Inside one service, "the money moved
and the record of it moved" is one commit and is free. Across the boundary it is
two commits in two systems, and **there is no ordering of two commits that is
safe**:

- write then publish → crash between them, and the money moved but nobody was told
- publish then write → the announcement describes a transfer that then rolled back

Everything below exists to replace that one lost guarantee with something weaker
but workable.

## 7. The outbox: remove the second system from the write path

The event is inserted into an `outbox` table **in the same transaction as the
entries**. It commits if and only if the money commits. Two outcomes, no window.

`OutboxWriter` is deliberately **not** `@Transactional`, and that absence is the
mechanism — it runs inside whatever transaction the caller already opened. Give
it `REQUIRES_NEW` and the guarantee is gone.

`event_id` is a column default (`gen_random_uuid()`), so it is minted by the
insert. The relay has no code path that *could* mint one, which matters because a
relay that generated ids would emit a fresh one every time it republished after a
crash and defeat every downstream dedupe.

A separate relay drains committed rows to Redpanda:

- **single-threaded, `ORDER BY id`** — so a hold's `Captured` cannot overtake its
  `Authorized` and reach a consumer that has never heard of the hold
- **marks published only after the broker acknowledges** — at-least-once, never
  at-most-once; marking first would trade a visible duplicate for a silent loss
- **stops the batch on the first failure** rather than skipping past it.
  Head-of-line blocking is the deliberate choice: a stuck relay is loud and shows
  up as a backlog that reconciliation reports, while a silently reordered stream
  is neither.

The outbox does not make Kafka transactional — nothing can. It removes the broker
from the write path, leaving one local transaction plus an at-least-once delivery
problem, and at-least-once plus a stable dedupe key is something a consumer can
actually solve.

The test that carries the argument is the one for a **refused** transfer: it
asserts no event at all. A publish-after-commit implementation passes every happy
path and fails only that one.

## 8. The inbox: exactly-once *effects*

Duplicates are guaranteed, not hypothetical. Two independent reasons: the relay
republishes after a crash between the ack and its own commit, and Kafka's
consumer offsets commit to the *broker* while the effect of consuming commits to
*our database* — two commits, no transaction across them.

So the question is never "can an event arrive twice". It is "does the second
arrival change anything".

```
BEGIN
  INSERT INTO inbox_events (event_id, ...) ON CONFLICT DO NOTHING
  0 rows → already seen; do nothing
  1 row  → run the handlers
COMMIT
```

Both orderings of a split version are broken, and it is worth being able to say
which way each fails: **effect-then-record** double-applies on redelivery;
**record-then-effect** loses the effect forever, silently, which is worse.

What is promised is not exactly-once *delivery* — nobody can offer that across a
network — but exactly-once **effects**, given at-least-once delivery and a stable
event id.

A record with no `event-id` header can never be processed and is dead-lettered on
the **first** attempt. Retrying it would block every later event on that partition
behind a record that can never succeed. Parking is quarantine, not handling, and
reconciliation reports a non-empty DLT.

## 9. The saga

Three steps: **authorize** a hold in the Ledger → **consume** the payer's spending
cap locally → **capture** the hold.

The middle step is the point. It is the only one whose effect commits in
Payments' own database, it sits between two remote effects it cannot be atomic
with, and when it refuses, the hold is already real. That is the moment a
compensating transaction becomes the only option.

Ordering it second rather than first is also right on its own merits: the most
common rejection is insufficient funds, and that answer lives in the Ledger.
Consuming a payer's cap for a payment the Ledger was going to refuse means
releasing it again on every decline.

**Compensation is not rollback.** `VOID` does not undo `AUTHORIZE`; it is a new,
forward operation with the opposite effect, and the Ledger records both. Rollback
is a property of a single transaction. Across services the best available is
"post the opposite", and pretending otherwise is how people convince themselves
they have distributed transactions.

Compensations run in **reverse order** and only for steps that actually
succeeded. Reverse matters concretely: the cap is released before the hold is
voided, so there is never an instant where the cap is free but the funds are
still reserved — which is exactly the window a customer retrying after a decline
would fall into.

`CAPTURE` has no compensator on purpose. Undoing it means posting a refund, a
business decision with its own authorization, not something a retry loop issues
at 4am. Capture is the point of no return.

### The deterministic key, and how it dissolves the 504

Each step's key is `saga:{sagaId}:{step}`. The saga id comes from the caller's
`Idempotency-Key` through a UNIQUE constraint and is persisted **before the first
step runs**. Each step's request body is persisted on first sight and resent
verbatim.

So a client retry and a driver retry both send the same key and the same body,
and both become *replays* at the Ledger.

This is what makes a read timeout ordinary. Payments still cannot know whether a
timed-out call landed — that ambiguity is not resolved and cannot be. It stopped
mattering. **The correct response to "I don't know" turned out not to be finding
out; it was making the question harmless.**

Persisting the body is not caching. The Ledger fingerprints request bodies and
answers `422 IDEMPOTENCY_KEY_REUSED` on a mismatch, so a retry that rebuilt its
body from state that had since moved on would not fail loudly — it would **wedge**
the saga permanently, which is much harder to notice.

### The driver

A poller, with no synchronous start path. `POST /payments` commits a saga and
returns `202`; the driver picks it up.

That costs one tick of latency and buys something worth far more: **recovery is
not a separate code path.** A saga resumed after a crash runs the same method in
the same order as one that never failed, so recovery is exercised by every test
rather than by the one test that remembers to kill something. Systems where
"resume" is its own routine are systems where resume is the least-tested code in
the building.

Claims are **leases**, not held transactions — push `next_attempt_at` forward and
commit, then make the HTTP call outside any transaction. Holding a database
transaction across a call to a slow peer is how a peer's problem becomes a
database problem.

Retries use exponential backoff with **full jitter**. Without jitter, an outage
synchronises every client that failed together and the retries become the second
outage.

**Forward steps and compensations get different retry budgets** (8 vs 50), and
the asymmetry is the argument: "declined" is a real answer to a forward step, so
giving up on one is a decision the system may make. There is no equivalent answer
for a compensation — the alternative to releasing a customer's funds is leaving
them reserved behind a payment that already failed. This came out of the chaos
run: with one shared budget, an outage long enough to decline a payment was also
long enough to fail its compensation.

### What the event stream is actually for

Worth being precise, because "we publish events" is easy to say and hard to
justify. Every other fact the saga needs arrives on the HTTP response of a
command it issued, and a lost response is handled by retrying.

**An expiry is different: nobody issued it.** The Ledger releases a lapsed hold on
its own, and no reply to any request Payments made will ever mention it. Without
the event, a saga whose hold expired would keep retrying `CAPTURE` against funds
already released, exhaust its budget, and report a timeout — the right ending for
the wrong reason, minutes late.

So the event stream carries the facts that **originate on the other side of the
boundary**. That is the job, and it is the only one it has here.

---

## 10. Failure modes

| What happens | What the system does |
|---|---|
| Ledger returns 4xx | terminal; the saga stops and compensates what succeeded |
| Ledger returns 5xx / unreachable | retryable — the request was not processed |
| Read timeout | retryable — outcome unknown, but the retry is a replay |
| Ledger killed mid-saga | driver retries on its lease; recovers with no intervention |
| Broker down | the Ledger keeps taking money; events pile in the outbox and drain later |
| Payments killed mid-saga | the saga row survives; another tick resumes from the last committed step |
| Event redelivered | inbox dedupes; no second effect |
| Poison message | dead-lettered on the first attempt; partition keeps moving |
| Hold expires mid-saga | learned from the event stream; saga turns around |
| Compensation cannot complete | `COMPENSATION_FAILED` — escalated, not retried away, not hidden |

**502 and 504 stay different answers at the API boundary** even though the saga
treats both as retryable. The saga can afford to conflate them because its retry
is safe either way; a *caller* still needs to know which happened.

## 11. How you know

Two independent answers, and neither is "the code is careful".

**Reconciliation** re-derives every invariant from raw rows — 7 checks in the
Ledger, 6 in Payments, two of the latter crossing the boundary over HTTP. None
asks the code that wrote the state whether it wrote it correctly.

Checking things the schema already enforces is deliberate: a trigger governs rows
written *through* it and says nothing about a migration, a 3am fix, or a future
path that took a shortcut — and a bug in the enforcement is invisible to the
enforcement. The tests prove this by disabling the trigger, writing bad rows, and
turning it back on.

The two cross-boundary checks are the bill for the split. Inside one service a
foreign key makes "this completed payment names a real ledger transaction"
impossible to violate. Across the boundary nothing can enforce it — which is
exactly why `payment_limits.account_id` is deliberately *not* a foreign key — so
it has to be verified after the fact.

**Chaos** (`chaos/chaos-test.sh`) SIGKILLs the Ledger mid-saga and asserts from
the `entries` table, then asks both services to reconcile as a second opinion. A
check that asks the system whether the system is correct is not a check.

The assertion to watch is **one `CAPTURE` transaction per completed payment**. If
the deterministic key were wrong — as it was before the saga existed — that is
the line that fails, and the payee balance would be wrong by the same amount.

## 12. What went wrong

The three worth telling, because all three are the same lesson.

**`@EnableScheduling` was missing.** The saga driver's trigger was never
registered, so every payment was accepted with `202` and then sat in `RUNNING`
forever — no exception, no failed step, no log line. All 14 saga tests passed,
because they drive `SagaDriver.runOnce()` directly. That is the *correct* way to
test a state machine — it is what lets them assert on the state between steps —
and it is precisely why not one of them could notice that nothing calls it in
production. Only the compose smoke test caught it.

**Test config was shadowing shipped config.** A file at
`src/test/resources/application.yml` does not merge with the main one; it
replaces it. It had been dropping the Kafka producer's `acks=all` in tests, and
would have left `LedgerTimeoutsTest` — which exists specifically to fail if the
HTTP timeouts are deleted — asserting a value the test's own config supplied. A
test that cannot fail.

**A connect timeout was reported as a read timeout.**
`HttpConnectTimeoutException` extends `HttpTimeoutException`, so a cause-chain
check in the obvious order classifies "never connected" as "connected, then
silence" — turning a definite 502 into an ambiguous 504. Every unit test stayed
green; the mock only ever threw the exception I had thought of. It surfaced
against a stopped container, by noticing the response came back at the 2s connect
timeout rather than the 5s read timeout.

**The lesson in all three: a test only exercises what it explicitly drives.**
Two of them were found by running the real thing, and the third by reading a
timestamp that did not match the configuration.

## 13. What I would do next

Ordered by what the design's own weaknesses demand, not by what is fun.

### Hardening the distributed claims

**Replace the polling relay with CDC.** The relay currently polls the outbox on a
timer. That was the right starting point — no extra infrastructure, and its
failure mode is a backlog anyone can see — but it costs up to a poll interval of
latency on every event and runs a query that usually finds nothing. Log-based
capture (Debezium reading the WAL) removes both. The important part is that the
`outbox` table does not change: only the reader does. That was the reason to put
events in a table rather than publish them inline.

The honest counterpoint, and the reason it has not been done: CDC moves a
correctness-relevant component out of the application and into infrastructure
that has to be operated, and it makes local development heavier. For two
services on one box, polling is the better trade. At a hundred, it is not.

**Shard hot accounts.** Per-account throughput is serial, because every debit
takes an ordered `FOR UPDATE` on the account row (ADR-0006). That is correct, not
accidental — it is what makes double-spend impossible — so the fix is not a
weaker lock. It is splitting a hot account into N sub-balance rows that are
debited independently and settle to the parent, converting lock contention into a
rebalancing problem. That trade is worth making only once a single account is
genuinely hot, which is why it is not made here.

**Run more than one saga driver.** Claims are leases, so a second instance should
already be safe: it would claim different rows, and even a double-driven step is
a replay because the step key is deterministic. "Should" is doing real work in
that sentence. The test is a two-instance run with a deliberately short lease, so
that double-driving is common rather than rare, asserting the capture count still
matches the completion count.

**Schema registry and explicit event versioning.** Events are JSON with no
registry, which is fine while both consumers live in this repository and neither
is really a third party. It stops being fine the moment a team you cannot deploy
with depends on the shape. Avro or Protobuf with a registry, plus a
backward-compatibility check in CI, turns "do not break the payload" from a
convention into a build failure.

**Redis in front of the hot reads — never in the write path of a money
movement.** Balance lookups and payment-status polling both re-derive their
answer from `entries` and `sagas` on every call, which is correct and is also
the first thing to cache once a read outnumbers its writes by enough to matter.
The scope has to be exact, though, and [ADR-0005](docs/adr/0005-no-redis-idempotency-in-postgres.md)
already drew the line: idempotency claims stay in Postgres, in the same
transaction as the money, because a claim that can commit independently of the
transfer it protects reintroduces the dual-write problem the outbox exists to
avoid. A read-through cache over *already-derived* data is a different thing —
nothing downstream depends on the cache being right, only on it agreeing with
Postgres — which is exactly why that ADR named this as the correct future use
rather than ruling Redis out entirely. The addition worth pairing with it is a
reconciliation check asserting `cached == derived`, so a stale cache is a
finding, not a silent wrong answer.

### Making it observable

Correctness here is currently *argued* in documents and *verified* in batch by
reconciliation. Neither is visible while it is happening.

**OpenTelemetry across both services**, with the saga id propagated as the trace
id. A stuck payment then becomes one span tree — accepted, authorized, waiting on
a retry, capture timing out — instead of a database query and an inference. This
is the single highest-value addition, because every failure mode in section 10 is
currently reasoned about rather than watched.

**Metrics shaped like the failure modes**, not generic dashboards: saga age
histogram (the stuck-saga check as a live signal instead of a periodic one),
outbox backlog, consumer lag, dead-letter depth, compensation rate, and the ratio
of retries to first-attempt successes.

**Alerting that inherits the RUNBOOK's distinction.** The five money invariants
page a human; the two operational ones (sweeper stopped, relay stuck) raise a
ticket. Collapsing those into one severity is how alert fatigue starts.

### Proving it harder

**Deterministic simulation testing.** The chaos test kills a real container, which
is convincing but not reproducible: it took a polling loop to make the kill land
mid-saga at all, and the timing still varies by machine. Driving the saga against
a simulated clock and network — where a partition, a reordering or 200ms of clock
skew is a seed rather than a race — turns "it survived once" into "it survives
this exact sequence, every time, and here is the seed." This is the approach
FoundationDB and TigerBeetle use, and TigerBeetle is a ledger, so the precedent is
directly on point.

**Model-check the saga state machine** in TLA+ or Alloy. The state space is small:
five saga states, five steps, three outcomes each. Small enough to check
exhaustively, which means a property like *no reachable state leaves funds
reserved with no saga owning them* can be settled rather than sampled. Tests
sample the state space; a model checker covers it. Given that the compensation
paths are the least-exercised code in the system, that is where exhaustiveness is
worth the most.

**Mutation testing** on the ledger core. The M1 invariants have good coverage by
line, but coverage measures execution, not constraint. Mutation testing answers
the question that actually matters: if I broke this, would anything fail?

### Payments domain depth

**Multi-currency and FX.** `Money` already refuses cross-currency arithmetic —
`add` throws on a currency mismatch rather than coercing — so the door is closed
in the right place, but there is nothing behind it. Doing this properly means the
FX rate *and its timestamp* are recorded
on the transaction, because a conversion that cannot be repriced later is not
auditable, and because the rate used is a fact about the payment rather than a
lookup that happens to be current.

**External reconciliation.** This is the biggest gap between this project and a
production ledger, and it is worth being blunt about. The reconciliation here
proves *self-consistency*: the ledger agrees with itself and Payments agrees with
the ledger. It cannot prove the money is actually where the ledger says it is.
That requires reconciling against an external source of truth — processor
settlement files, bank statements, card network reports, ISO 20022 messages —
and handling the cases internal checks never see: a payment the processor knows
about and we do not, fees deducted at settlement, timing differences across a
cutover. Everything in this repository is the half of reconciliation you can do
without a counterparty.

**The rest of the card lifecycle:** disputes and chargebacks (which are
compensations with a legal clock attached), incremental and partial
authorizations, authorization reversals, and a fee and interchange model.

### Cloud deployment

This runs on one laptop by design, not by limitation — free to build, free to
review, `git clone && docker compose up` and nothing needs hosting. That is
stated in the interest of not overclaiming, not as an argument that cloud
deployment is unnecessary: it is the next thing this needs, and what changes is
specific rather than an abstract "move it to the cloud."

- **Postgres → RDS/Aurora Postgres, Multi-AZ.** One instance per service exactly
  as now (ADR-0016) — the two-database boundary is a modelling decision, not a
  deployment one, so a managed database changes who runs the box and nothing
  about the argument that no transaction can span the two.
- **Redpanda → MSK, or Redpanda's own managed offering.** The outbox and inbox
  are written against the Kafka API, not against Redpanda specifically
  (ADR-0004), so this is a bootstrap-server change.
- **Ledger and Payments → ECS Fargate or EKS**, one task per service. Horizontal
  scale-out on the Payments side is the same lease-based claim that is already
  meant to make a second local saga driver instance safe — this is the same
  change, at a different unit of deployment.
- **Secrets → Secrets Manager or Vault**, rotated, in place of the compose
  environment variables the demo uses.
- **The chaos test still runs, unmodified in its assertions.** `chaos/chaos-test.sh`
  would target the ECS/EKS API to kill a task instead of `docker kill`ing a
  container; every assertion after that point — read the entries table, ask both
  services to reconcile — was never about Docker and does not change.

The reason this is not built is the same reason nothing else in "production
posture" is: it would not change what the project is arguing. The argument is
about the correctness of the mechanism under failure, and a laptop can kill a
container exactly as authentically as a cluster can kill a task. What cloud
deployment adds is operational reality — real network partitions instead of a
Docker bridge, real multi-AZ failover, a real bill — which is worth having next,
not worth pretending this laptop already has.

### Other production posture

Deliberately out of scope for a portfolio artifact, listed so the omissions read
as decisions: Kubernetes canary deploys and a rollback story; mTLS between
services and OAuth2/OIDC on the operator endpoints; expand/contract migrations so
schema changes are zero-downtime; field-level encryption and tokenization for
anything PII-adjacent; multi-region with a stated RPO and RTO; and an
idempotency-key retention policy, because `idempotency_keys` currently grows
forever.
