# Chaos and smoke scripts

Two scripts, both run against the real `docker compose` stack.

```bash
./chaos/smoke-test.sh          # one payment, end to end
./chaos/chaos-test.sh          # kill the Ledger mid-saga, prove nothing was lost
./chaos/demo.sh                # the narrated version, for a recording or a live walkthrough
```

Both take `--no-build` to skip the image rebuild. `chaos-test.sh` also takes
`--payments N` (default 12) and `--outage SECONDS` (default 8).

Requires `docker compose`, `curl` and `python3`.

---

## `demo.sh` — for humans, not for CI

`chaos-test.sh` proves the claim: 12 concurrent payments, killed mid-flight,
every invariant asserted from the database. That is rigor a human watching 90
seconds of terminal doesn't need and can't follow.

`demo.sh` tells the same story with one payment and a banner between each
beat: the balance before, the payment accepted, the kill, Payments staying up
without its dependency, the outage, the restart, the saga finishing on its
own with no retry command typed by hand, the balance after, and both
services' reconciliation reporting `healthy: true`. On this machine it runs
end to end in about 25 seconds — comfortably inside the README's 60-90s clip
budget with room to breathe.

Record it with [asciinema](https://asciinema.org/) rather than screen-capture
video — a terminal recording is real bytes, not an edited claim, which is the
right kind of proof for this audience:

```bash
asciinema rec chaos/demo.cast -c "./chaos/demo.sh --no-build"
```

Convert to a GIF for the README with [`agg`](https://github.com/asciinema/agg)
(`agg chaos/demo.cast chaos/demo.gif`), or embed the `.cast` directly via the
asciinema player.

For the live 3-minute walkthrough, run it unrecorded and narrate the parts it
doesn't say out loud: the deterministic step key, why 502 and 504 are
different answers, why compensations run in reverse. If asked "what if it
doesn't come back in time," switch to `chaos-test.sh --outage 75` live — that
run declines some payments and shows the compensation path unwinding for
real.

## Why these are scripts and not JUnit tests

Everything else in this repository proves a piece. The unit tests prove the
saga's decisions, `MockRestServiceServer` proves the HTTP translation, and
Testcontainers proves the SQL against a real Postgres. None of them proves that
two services, two databases and a broker fit together over a network, and none
of them can: a test that started both services would couple each module's build
to the other's code, and would still run over loopback rather than the compose
network these scripts sever.

What they exercise is the deployment, not the code. That is why they live here.

---

## What `chaos-test.sh` actually does

1. Seeds a known ledger state, so every number it later asserts is derivable
   from `seed-ledger.sql` rather than from whatever was left over.
2. Fires N payments concurrently and then **polls for the kill moment** rather
   than sleeping to it: it kills as soon as at least one hold exists and not
   every payment has captured. That is the definition of "mid-saga", and it is
   deterministic on any machine instead of tuned to one.

   The first version slept a fixed 1.5s. On a fast machine every saga had
   already finished, so the chaos test killed an idle service and asserted that
   nothing had gone wrong — true, and proof of nothing. The script noticed,
   because it checks and reports the state at the moment of the kill. That check
   is worth more than the sleep it replaced.
3. `docker kill` on the Ledger. SIGKILL, not `stop`: no graceful shutdown, no
   chance to finish an in-flight request or flush anything. A service that only
   survives being asked politely to leave has not been tested.
4. Checks Payments stayed healthy while its dependency was gone.
5. Restarts the Ledger, waits for the healthcheck, and polls every saga to a
   terminal state.
6. Asserts the invariants **by reading the entries table directly**, then asks
   both services to reconcile as a second opinion. A check that asks the system
   whether the system is correct is not a check.

## What it claims, and what it does not

It does **not** claim every payment succeeds. An outage longer than the forward
retry budget legitimately declines payments, and pretending otherwise would
hide the trade instead of making it. What it claims is stronger and is the only
thing that matters:

> the money that moved is exactly the money the payments say moved.

Concretely, and asserted every run:

| Assertion | Why it is the interesting one |
|---|---|
| payee balance = amount × completed | money did not appear |
| payer balance = start − amount × completed | money did not vanish |
| ledger nets to zero | double-entry survived the kill |
| CAPTURE transactions = completed payments | **no retry became a second charge** |
| no hold still reserving funds | no declined customer is left frozen |
| no failed payment still holding a spending cap | no local state leaked |
| outbox fully drained | the event pipeline caught up after the restart |
| distinct inbox events = inbox rows | at-least-once delivery stayed exactly-once in effect |

The capture-count assertion is the one to watch. If the deterministic step key
were wrong — if a retry sent a fresh key, as it did before the saga existed —
this is the line that would fail, and the payee balance would be wrong by the
same amount.

## Two runs worth doing

```bash
./chaos/chaos-test.sh --no-build              # short outage: full recovery
./chaos/chaos-test.sh --no-build --outage 75  # long outage: declines and compensation
```

Observed on a laptop, both passing every assertion:

| Run | at the kill | outcome | ledger net |
|---|---|---|---|
| 8s outage | 12 in flight, 11 holds, 2 captured | 12 completed | 0 |
| 75s outage | 10 in flight, 11 holds, 2 captured | 8 completed, 4 compensated | 0 |

The second is the more interesting one. Four payments outlived the forward retry
budget, were declined, and had their holds voided and their spending caps
released once the Ledger returned — captures matched completions exactly, and no
hold was left reserving funds. That is the compensation path running against a
real outage rather than a scripted 422.

## Why an ordinary run still shows compensations

Restarting a Spring Boot service takes longer than a few retries. Payments whose
authorization landed before the kill, and whose capture then exhausted its
retry budget, are declined and compensated: the spending cap is released and the
hold is voided once the Ledger returns. Those runs are the more interesting
ones, because they exercise the compensation path against a real outage rather
than against a scripted 422.

Compensations get a much larger retry budget than forward steps
(`spademoney.saga.compensation-max-attempts`), and that asymmetry came directly
out of running this script. With one shared budget, an outage long enough to
decline a payment was also long enough to fail its compensation — and the system
escalated a dead end it would have cleared itself given a few more seconds.
"Declined" is an acceptable answer for a forward step; there is no acceptable
alternative to releasing a customer's funds.

## Known limitation

Both databases live in one Postgres container, so they share a failure domain.
That is a laptop concession and it does not weaken this test, which kills an
*application* container. A deployment with separate database hosts would change
nothing the script asserts.
