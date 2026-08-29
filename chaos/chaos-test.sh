#!/usr/bin/env bash
#
# The killer demo: kill the Ledger mid-saga, bring it back, and prove that no
# money was lost or duplicated.
#
#   ./chaos/chaos-test.sh [--payments N] [--outage SECONDS] [--no-build]
#
# What it does, and why each part is there:
#
#   1. Seeds a known ledger state, so every number asserted later is derivable.
#   2. Fires N payments concurrently and lets them get into flight -- some will
#      have taken their hold, some will not, some will be mid-capture. That
#      spread is the point; killing a system between two known steps proves much
#      less than killing it across all of them at once.
#   3. `docker kill` the Ledger. Not `stop`: SIGKILL, no graceful shutdown, no
#      chance to finish an in-flight request or flush anything. A service that
#      only survives being asked politely to leave has not been tested.
#   4. Waits out the outage, restarts, waits for health.
#   5. Polls every payment to a terminal state.
#   6. Asserts the invariants -- from the DATABASE, not from the services'
#      own reports -- and then also asks both services to reconcile.
#
# WHAT IS AND IS NOT CLAIMED
#
# It is NOT claimed that every payment succeeds. An outage longer than the
# forward retry budget legitimately declines payments, and a system that
# pretended otherwise would be hiding the trade rather than making it. What is
# claimed is much stronger and is the only thing that matters:
#
#   the money that moved is exactly the money the payments say moved.
#
# Every SUCCEEDED payment debited its payer exactly once. Every FAILED payment
# moved nothing and left no funds reserved. The ledger nets to zero. Those hold
# whatever the mix of outcomes, which is why they are the assertions.
#
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PAYMENT_COUNT=12
OUTAGE_SECONDS=8
KILL_AFTER=1.5
AMOUNT=2500
BUILD=1
RUN_ID="chaos-$(date +%s)"

while [ $# -gt 0 ]; do
    case "$1" in
        --payments) PAYMENT_COUNT="$2"; shift 2 ;;
        --outage)   OUTAGE_SECONDS="$2"; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        *) say "unknown argument: $1"; exit 2 ;;
    esac
done

step "Bringing up the stack"
if [ "$BUILD" -eq 1 ]; then compose up -d --build; else compose up -d; fi
wait_healthy postgres
wait_healthy redpanda
wait_healthy ledger
wait_healthy payments
ok "all four services healthy"

step "Seeding a known state"
psql_ledger_file "$(dirname "${BASH_SOURCE[0]}")/seed-ledger.sql"
psql_payments_file "$(dirname "${BASH_SOURCE[0]}")/seed-payments.sql"
PAYER_START="$(psql_ledger "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries WHERE account_id = 2")"
say "        payer(2) = $PAYER_START  payee(3) = 0  amount per payment = $AMOUNT"

step "Firing $PAYMENT_COUNT payments"
IDS_FILE="$(mktemp)"
for i in $(seq 1 "$PAYMENT_COUNT"); do
    (
        response="$(curl -fsS -X POST "$PAYMENTS_URL/payments" \
            -H 'Content-Type: application/json' \
            -H "Idempotency-Key: $RUN_ID-$i" \
            -d "{\"payerAccountId\":2,\"payeeAccountId\":3,\"amountMinor\":$AMOUNT,\"currency\":\"USD\"}" 2>/dev/null)" || exit 0
        printf '%s\n' "$(printf '%s' "$response" | json paymentId)" >> "$IDS_FILE"
    ) &
    sleep 0.1
done
wait
ACCEPTED="$(wc -l < "$IDS_FILE" | tr -d ' ')"
ok "$ACCEPTED payment(s) accepted"

step "Killing the Ledger mid-saga"
sleep "$KILL_AFTER"
in_flight="$(psql_payments "SELECT count(*) FROM sagas WHERE status IN ('RUNNING','COMPENSATING')")"
holds_taken="$(psql_ledger "SELECT count(*) FROM holds")"
say "        at the moment of the kill: $in_flight saga(s) in flight, $holds_taken hold(s) already placed"
if [ "$in_flight" -gt 0 ] && [ "$holds_taken" -gt 0 ] && [ "$holds_taken" -lt "$ACCEPTED" ]; then
    ok "the kill lands mid-flight, with sagas on both sides of their first step"
else
    # Not a failure of the SYSTEM -- a failure of the experiment to be
    # interesting. Say so plainly rather than quietly asserting something weaker.
    warn "the kill did not straddle the first step (in flight: $in_flight, holds: $holds_taken of $ACCEPTED)"
fi

# SIGKILL. No graceful shutdown, no chance to finish an in-flight request.
compose kill ledger >/dev/null
ok "ledger killed (SIGKILL)"

step "Riding out a ${OUTAGE_SECONDS}s outage"
sleep "$OUTAGE_SECONDS"

# What Payments does while its dependency is gone. It should be answering, and
# it should be refusing to guess.
if curl -fsS -o /dev/null "$PAYMENTS_URL/actuator/health"; then
    ok "Payments stayed healthy without the Ledger"
else
    bad "Payments went unhealthy when the Ledger died -- it should not depend on it to be up"
fi

step "Restarting the Ledger"
compose start ledger >/dev/null
wait_healthy ledger
ok "ledger is back"

step "Waiting for every saga to reach a terminal state"
DEADLINE=$((SECONDS + 240))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
    pending="$(psql_payments "SELECT count(*) FROM sagas WHERE status IN ('RUNNING','COMPENSATING')")"
    [ "$pending" = "0" ] && break
    sleep 2
done
assert_eq "0" "$pending" "no saga is still in flight"

SUCCEEDED="$(psql_payments "SELECT count(*) FROM sagas WHERE status = 'COMPLETED'")"
COMPENSATED="$(psql_payments "SELECT count(*) FROM sagas WHERE status = 'COMPENSATED'")"
FAILED="$(psql_payments "SELECT count(*) FROM sagas WHERE status = 'FAILED'")"
say "        completed=$SUCCEEDED compensated=$COMPENSATED failed=$FAILED of $ACCEPTED"

step "The money is right"
# Read straight from the entries table. Deliberately not from either service's
# reconciliation endpoint: a check that asks the system whether it is correct is
# not a check. Reconciliation is run afterwards, as a second opinion.
payer_end="$(psql_ledger "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries WHERE account_id = 2")"
payee_end="$(psql_ledger "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries WHERE account_id = 3")"
zero_sum="$(psql_ledger "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries")"

expected_moved=$((SUCCEEDED * AMOUNT))
assert_eq "$expected_moved" "$payee_end" "the payee received exactly what succeeded"
assert_eq "$((PAYER_START - expected_moved))" "$payer_end" "the payer paid exactly what succeeded"
assert_eq "0" "$zero_sum" "the ledger nets to zero"

# Nothing was applied twice. One CAPTURE transaction per completed payment: if a
# retry had produced a second effect instead of a replay, this is where it would
# show, and the payee balance above would be wrong by the same amount.
captures="$(psql_ledger "SELECT count(*) FROM transactions WHERE type = 'CAPTURE'")"
assert_eq "$SUCCEEDED" "$captures" "one capture per completed payment, no duplicates"

step "Nothing is left reserved"
# Every hold reached a terminal state: captured for the payments that succeeded,
# voided or expired for the ones that did not. An ACTIVE hold here would mean a
# declined customer whose funds are still tied up.
active_holds="$(psql_ledger "SELECT count(*) FROM holds WHERE status = 'ACTIVE' AND expires_at > now()")"
assert_eq "0" "$active_holds" "no hold is still reserving funds"
assert_eq "$SUCCEEDED" "$(psql_ledger "SELECT count(*) FROM holds WHERE status = 'CAPTURED'")" \
    "captured holds match completed payments"

unreleased="$(psql_payments "SELECT count(*) FROM limit_consumptions c JOIN sagas s ON s.id = c.saga_id WHERE c.released_at IS NULL AND s.status IN ('FAILED','COMPENSATED')")"
assert_eq "0" "$unreleased" "no failed payment is still holding a spending cap"

step "The event pipeline caught up"
# The Ledger kept accepting work while the broker connection and the process
# itself were gone; the outbox is what let it. If the relay had not drained
# after the restart, this is where it would show.
for _ in $(seq 1 45); do
    backlog="$(psql_ledger "SELECT count(*) FROM outbox WHERE published_at IS NULL")"
    [ "$backlog" = "0" ] && break
    sleep 2
done
assert_eq "0" "$backlog" "the outbox fully drained after the restart"

events="$(psql_ledger "SELECT count(*) FROM outbox")"
consumed="$(psql_payments "SELECT count(*) FROM inbox_events")"
distinct="$(psql_payments "SELECT count(DISTINCT event_id) FROM inbox_events")"
say "        ledger published $events event(s); payments recorded $consumed"
assert_eq "$consumed" "$distinct" "every consumed event is recorded exactly once"

step "Second opinion: both services reconcile"
assert_reconciliation_clean "ledger" "$LEDGER_URL"
assert_reconciliation_clean "payments" "$PAYMENTS_URL"

step "Result"
say "  payments accepted     $ACCEPTED"
say "  completed             $SUCCEEDED"
say "  compensated / failed  $((COMPENSATED + FAILED))"
say "  moved                 $expected_moved minor units"
say "  ledger net            $zero_sum"
say "  outage                ${OUTAGE_SECONDS}s, SIGKILL, mid-saga"

rm -f "$IDS_FILE"
summary
