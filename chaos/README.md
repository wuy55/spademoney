# Chaos and smoke scripts

Two scripts, both run against the real `docker compose` stack.

```bash
./chaos/smoke-test.sh          # one payment, end to end
./chaos/chaos-test.sh          # kill the Ledger mid-saga, prove nothing was lost
```

Both take `--no-build` to skip the image rebuild. `chaos-test.sh` also takes
`--payments N` (default 12) and `--outage SECONDS` (default 8).

Requires `docker compose`, `curl` and `python3`. Deliberately not `jq` — it is
the tool most likely to be missing on a reviewer's laptop, and "install jq
first" is a poor opening line for a script whose whole purpose is to be run by
somebody else.

---

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
2. Fires N payments concurrently and lets them get into flight. Some will have
   taken their hold, some will not, some will be mid-capture. That spread is
   the point — killing a system between two known steps proves much less than
   killing it across all of them at once. The script checks that the kill
   really did straddle the first step, and says so if it did not.
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
