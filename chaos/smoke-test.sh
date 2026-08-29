#!/usr/bin/env bash
#
# The happy path, end to end, over the real compose stack.
#
# Everything else in this repository proves a piece: unit tests prove the saga's
# decisions, MockRestServiceServer proves the HTTP translation, Testcontainers
# proves the SQL. None of them prove that the two services, two databases and a
# broker actually fit together over a network -- deliberately, because a test
# that starts both services would be slow, would couple each module's build to
# the other's code, and would still run over loopback rather than the compose
# network the chaos test kills.
#
# This is the test that closes that gap, and it is a script rather than a JUnit
# class for the same reason: what it exercises is the deployment, not the code.
#
#   ./chaos/smoke-test.sh [--no-build]
#
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BUILD=1
[ "${1:-}" = "--no-build" ] && BUILD=0

AMOUNT=2500

step "Bringing up the stack"
if [ "$BUILD" -eq 1 ]; then
    compose up -d --build
else
    compose up -d
fi
wait_healthy postgres
wait_healthy redpanda
wait_healthy ledger
wait_healthy payments
ok "postgres, redpanda, ledger and payments are healthy"

step "Seeding"
psql_ledger_file "$(dirname "${BASH_SOURCE[0]}")/seed-ledger.sql"
psql_payments_file "$(dirname "${BASH_SOURCE[0]}")/seed-payments.sql"
payer_before="$(psql_ledger "SELECT balance_minor FROM (SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) AS balance_minor FROM entries WHERE account_id = 2) s")"
assert_eq "1000000" "$payer_before" "payer starts funded"

step "One payment, start to finish"
response="$(curl -fsS -i -X POST "$PAYMENTS_URL/payments" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-$(date +%s)" \
    -d "{\"payerAccountId\":2,\"payeeAccountId\":3,\"amountMinor\":$AMOUNT,\"currency\":\"USD\"}")"

status_line="$(printf '%s' "$response" | head -1 | tr -d '\r')"
location="$(printf '%s' "$response" | awk 'tolower($1) == "location:" { print $2 }' | tr -d '\r')"
body="$(printf '%s' "$response" | sed -n '/^\r*$/,$p' | tail -n +2)"

case "$status_line" in
    *202*) ok "POST /payments answered 202 Accepted" ;;
    *)     bad "POST /payments answered: $status_line" ;;
esac
[ -n "$location" ] && ok "Location: $location" || bad "no Location header"

payment_id="$(printf '%s' "$body" | json paymentId)"

# Poll the resource the Location header names. It resolves, which is the whole
# reason the header was withheld until the saga existed.
step "Polling until the saga finishes"
final=""
for _ in $(seq 1 60); do
    view="$(curl -fsS "$PAYMENTS_URL/payments/$payment_id")"
    final="$(printf '%s' "$view" | json status)"
    [ "$final" = "PENDING" ] || break
    sleep 1
done
assert_eq "SUCCEEDED" "$final" "the payment succeeded"

saga_status="$(printf '%s' "$view" | json sagaStatus)"
txn="$(printf '%s' "$view" | json ledgerTransactionId)"
hold="$(printf '%s' "$view" | json holdId)"
say "        saga=$saga_status hold=$hold ledgerTransaction=$txn"

step "The money moved, in the Ledger"
payer_after="$(psql_ledger "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries WHERE account_id = 2")"
payee_after="$(psql_ledger "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries WHERE account_id = 3")"
assert_eq "$((1000000 - AMOUNT))" "$payer_after" "payer was debited exactly once"
assert_eq "$AMOUNT" "$payee_after" "payee was credited exactly once"

# The Ledger's own view of the same transaction, over its published API.
curl -fsS -o /dev/null "$LEDGER_URL/transfers/$txn" \
    && ok "the Ledger confirms transaction $txn" \
    || bad "the Ledger does not know transaction $txn"

step "The Ledger saw a derived key, never the caller's"
keys="$(psql_ledger "SELECT string_agg(idempotency_key, ', ') FROM idempotency_keys")"
case "$keys" in
    *"saga:$payment_id:AUTHORIZE"*) ok "authorize key: saga:$payment_id:AUTHORIZE" ;;
    *) bad "expected a saga-derived authorize key, found: $keys" ;;
esac
case "$keys" in
    *smoke-*) bad "the caller's key leaked into the Ledger: $keys" ;;
    *) ok "the caller's key was never forwarded" ;;
esac

step "The event pipeline delivered"
for _ in $(seq 1 30); do
    consumed="$(psql_payments "SELECT count(*) FROM inbox_events")"
    [ "$consumed" -ge 2 ] && break
    sleep 1
done
[ "${consumed:-0}" -ge 2 ] \
    && ok "Payments consumed $consumed ledger event(s) (authorize + capture)" \
    || bad "Payments consumed only ${consumed:-0} event(s)"
assert_eq "0" "$(psql_ledger "SELECT count(*) FROM outbox WHERE published_at IS NULL")" "the outbox drained"

step "Reconciliation"
assert_reconciliation_clean "ledger" "$LEDGER_URL"
assert_reconciliation_clean "payments" "$PAYMENTS_URL"

summary
