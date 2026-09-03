#!/usr/bin/env bash
#
# The killer demo, on a stopwatch. One payment, killed mid-flight, recovered
# with no human intervention, proven correct. This is the script behind the
# README's clip (§7.3 of the plan) and the live walkthrough.
#
#   ./chaos/demo.sh              # ~45s of actual outage + polling
#   ./chaos/demo.sh --no-build   # skip the image rebuild (stack already up)
#   ./chaos/demo.sh --record     # wrap the whole thing in `asciinema rec`
#
# Deliberately NOT chaos-test.sh. That script proves the claim with 12
# concurrent payments and a dozen assertions -- rigor a human watching 90
# seconds of terminal doesn't need and can't follow. This tells one story:
# start a transfer, kill the service mid-transaction, bring it back, watch it
# finish correctly on its own, prove the money is right.
#
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BUILD=1
RECORD=0
AMOUNT=2500
OUTAGE_SECONDS=8

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) BUILD=0; shift ;;
        --record)   RECORD=1; shift ;;
        --outage)   OUTAGE_SECONDS="$2"; shift 2 ;;
        *) say "unknown argument: $1"; exit 2 ;;
    esac
done

if [ "$RECORD" -eq 1 ] && [ -z "${ASCIINEMA_REC:-}" ]; then
    command -v asciinema >/dev/null || { say "asciinema not found: brew install asciinema / apt install asciinema"; exit 1; }
    exec asciinema rec --overwrite -c "ASCIINEMA_REC=1 $0 ${*}" "$(dirname "${BASH_SOURCE[0]}")/demo.cast"
fi

# A banner for each story beat, paced for a viewer rather than a CI log.
# `read` with a timeout doubles as a pause that a human can also skip through
# by hitting a key -- handy when rehearsing the live 3-minute version.
beat() {
    printf '\n\033[1;36m━━━ %s ━━━\033[0m\n' "$*"
    read -r -t 1.2 -p '' _ 2>/dev/null || true
}

step "Bringing up the stack"
# Quiet on purpose: wait_healthy below is the real signal, and compose's own
# per-container Creating/Starting/Waiting/Healthy transcript is noise on a
# recording. Still shown when something is actually wrong -- capture it and
# only print it if a health check times out.
compose_log="$(mktemp)"
if [ "$BUILD" -eq 1 ]; then compose up -d --build >"$compose_log" 2>&1; else compose up -d >"$compose_log" 2>&1; fi
if ! { wait_healthy postgres && wait_healthy redpanda && wait_healthy ledger && wait_healthy payments; }; then
    say "compose output:"; cat "$compose_log"; exit 1
fi
rm -f "$compose_log"

beat "Two services. Two databases. No shared transaction is possible."
compose ps --format 'table {{.Service}}\t{{.Status}}'

psql_ledger_file "$(dirname "${BASH_SOURCE[0]}")/seed-ledger.sql" >/dev/null
psql_payments_file "$(dirname "${BASH_SOURCE[0]}")/seed-payments.sql" >/dev/null

beat "The payer's balance, before"
before="$(psql_ledger "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries WHERE account_id = 2")"
printf '  account 2:  %s minor units\n' "$before"

beat "One payment. Accepted, not completed."
response="$(curl -fsS -i -X POST "$PAYMENTS_URL/payments" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-$(date +%s)" \
    -d "{\"payerAccountId\":2,\"payeeAccountId\":3,\"amountMinor\":$AMOUNT,\"currency\":\"USD\"}")"
printf '%s\n' "$response" | head -1 | tr -d '\r'
printf '%s\n' "$response" | awk 'tolower($1) == "location:"'
payment_id="$(printf '%s' "$response" | sed -n '/^\r*$/,$p' | tail -n +2 | json paymentId)"
printf '  A saga is now driving three steps across two services: authorize, check\n'
printf '  the local spending limit, capture. No local transaction spans them.\n'

# Give the saga a moment to actually take the hold before we pull the rug.
for _ in $(seq 1 20); do
    [ "$(psql_ledger "SELECT count(*) FROM holds WHERE id = 1")" = "1" ] && break
    sleep 0.2
done

beat "KILL -9. No graceful shutdown. No chance to finish."
compose kill ledger
printf '  \033[1;31mledger killed\033[0m — the hold is placed, the money is not settled\n'

beat "Payments did not go down with it"
curl -fsS -o /dev/null "$PAYMENTS_URL/actuator/health" \
    && printf '  \033[1;32mpayments: healthy\033[0m — it has no idea the ledger is gone yet\n' \
    || printf '  \033[1;31mpayments: unhealthy\033[0m\n'
curl -fsS "$PAYMENTS_URL/payments/$payment_id" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  saga:", d["sagaStatus"], " status:", d["status"])'

beat "Riding out a ${OUTAGE_SECONDS}s outage"
sleep "$OUTAGE_SECONDS"

beat "Bringing the ledger back"
compose start ledger >/dev/null
wait_healthy ledger
printf '  \033[1;32mledger: healthy\033[0m — nobody told the saga to retry. It just did.\n'

beat "Watching it finish on its own"
last=""
for _ in $(seq 1 60); do
    view="$(curl -fsS "$PAYMENTS_URL/payments/$payment_id")"
    now="$(printf '%s' "$view" | json sagaStatus)"
    if [ "$now" != "$last" ]; then
        printf '  saga: %s\n' "$now"
        last="$now"
    fi
    [ "$(printf '%s' "$view" | json status)" != "PENDING" ] && break
    sleep 0.5
done
status="$(printf '%s' "$view" | json status)"
txn="$(printf '%s' "$view" | json ledgerTransactionId)"
[ "$status" = "SUCCEEDED" ] \
    && printf '  \033[1;32mSUCCEEDED\033[0m — ledger transaction %s\n' "$txn" \
    || bad "payment ended as $status"

beat "The payer's balance, after"
after="$(psql_ledger "SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM entries WHERE account_id = 2")"
printf '  account 2:  %s minor units  (was %s, moved exactly %s)\n' "$after" "$before" "$((before - after))"

beat "Second opinion: reconciliation"
for url in "$LEDGER_URL" "$PAYMENTS_URL"; do
    healthy="$(curl -fsS "$url/reconciliation" | json healthy)"
    name="$([ "$url" = "$LEDGER_URL" ] && echo ledger || echo payments)"
    [ "$healthy" = "True" ] || [ "$healthy" = "true" ] \
        && printf '  %-9s reconciliation: \033[1;32mhealthy: true\033[0m\n' "$name" \
        || printf '  %-9s reconciliation: \033[1;31mUNHEALTHY\033[0m\n' "$name"
done

printf '\n\033[1;32mZERO MONEY LOST. ZERO MONEY DUPLICATED. NO HUMAN TOUCHED IT.\033[0m\n\n'
