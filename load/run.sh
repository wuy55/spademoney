#!/usr/bin/env bash
#
# Runs the k6 load test against the compose stack and writes load/report.md.
#
#   ./load/run.sh                        # defaults: 50 req/s for 30s
#   ./load/run.sh --rate 80 --duration 60s
#   ./load/run.sh --no-report            # run without rewriting report.md
#
# Every number in report.md comes from this script. That is the point: the plan's
# principle 7 is that a published figure must be reproducible from a committed
# script, so there is no step here where a human reads a number off a screen and
# types it into a document.
#
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../chaos" && pwd)/lib.sh"

LOAD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RATE=50
DURATION=30s
AMOUNT_MINOR=1
WRITE_REPORT=1
PAYER_COUNT=10

while [ $# -gt 0 ]; do
    case "$1" in
        --rate)      RATE="$2"; shift 2 ;;
        --duration)  DURATION="$2"; shift 2 ;;
        --no-report) WRITE_REPORT=0; shift ;;
        *) say "unknown argument: $1"; exit 2 ;;
    esac
done

step "Checking the stack"
wait_healthy postgres; wait_healthy redpanda; wait_healthy ledger; wait_healthy payments
ok "all four services healthy"

step "Preparing load accounts"
# Idempotent: tops the wallet count up to what the run needs rather than creating
# a fresh batch each time, so repeated runs do not accumulate accounts.
psql_ledger "
DO \$\$
DECLARE wallets INT; cash_id BIGINT; txn BIGINT; w RECORD;
BEGIN
    SELECT count(*) INTO wallets FROM accounts WHERE type = 'USER_WALLET';
    IF wallets < $((PAYER_COUNT + 1)) THEN
        INSERT INTO accounts (type, currency)
        SELECT 'USER_WALLET', 'USD' FROM generate_series(1, $((PAYER_COUNT + 1)) - wallets);
    END IF;

    SELECT id INTO cash_id FROM accounts WHERE type = 'CASH' ORDER BY id LIMIT 1;

    -- Top every wallet up to a balance the run cannot exhaust. Funded as balanced
    -- double entries, like everything else -- the deferred entries_balanced
    -- trigger would refuse anything less, and seeding through the same rules is
    -- what keeps the zero-sum assertion at the end meaningful.
    FOR w IN SELECT id FROM accounts WHERE type = 'USER_WALLET' LOOP
        INSERT INTO transactions (type) VALUES ('TRANSFER') RETURNING id INTO txn;
        INSERT INTO entries (transaction_id, account_id, direction, amount_minor, currency) VALUES
            (txn, w.id,    'CREDIT', 10000000, 'USD'),
            (txn, cash_id, 'DEBIT',  10000000, 'USD');
    END LOOP;
END \$\$;" >/dev/null

PAYER_IDS="$(psql_ledger "SELECT string_agg(id::text, ',') FROM (SELECT id FROM accounts WHERE type='USER_WALLET' ORDER BY id LIMIT $PAYER_COUNT) t")"
PAYEE_ID="$(psql_ledger "SELECT id FROM accounts WHERE type='USER_WALLET' ORDER BY id DESC LIMIT 1")"
ok "payers: $PAYER_IDS -> payee: $PAYEE_ID"

sagas_before="$(psql_payments "SELECT count(*) FROM sagas")"
completed_before="$(psql_payments "SELECT count(*) FROM sagas WHERE status='COMPLETED'")"
failed_before="$(psql_payments "SELECT count(*) FROM sagas WHERE status IN ('FAILED','COMPENSATED')")"
entries_before="$(psql_ledger "SELECT count(*) FROM entries")"

step "k6: ${RATE} req/s for ${DURATION}"
# --user maps the container to the host uid/gid. Without it k6 runs as its own
# unprivileged user and cannot write summary.json into the bind mount, which
# fails silently enough that the run still looks successful.
docker run --rm -i \
    --user "$(id -u):$(id -g)" \
    --network spademoney_default \
    -v "$LOAD_DIR:/load" \
    -e PAYMENTS_URL=http://payments:8081 \
    -e PAYER_IDS="$PAYER_IDS" \
    -e PAYEE_ID="$PAYEE_ID" \
    -e AMOUNT_MINOR="$AMOUNT_MINOR" \
    -e RATE="$RATE" \
    -e DURATION="$DURATION" \
    grafana/k6 run --quiet /load/payments-load.js 2>&1 | tee "$LOAD_DIR/k6-output.txt"

# The arrival phase is over; the sagas it created are still draining. This is the
# number the accept-path latency above deliberately excludes, and reporting only
# one of the two would be the misleading version of this test.
step "Draining sagas"
drain_start=$SECONDS
accepted="$(psql_payments "SELECT count(*) FROM sagas")"
accepted=$((accepted - sagas_before))
while [ $((SECONDS - drain_start)) -lt 600 ]; do
    pending="$(psql_payments "SELECT count(*) FROM sagas WHERE status IN ('RUNNING','COMPENSATING')")"
    [ "$pending" = "0" ] && break
    sleep 2
done
drain_seconds=$((SECONDS - drain_start))
assert_eq "0" "$pending" "every saga reached a terminal state"

# Deltas, not table totals. The first version of this reported the global
# COMPLETED count against this run's accepted count and produced "1500 accepted,
# 1501 completed" -- a number that cannot happen, caused by a payment from an
# earlier manual test still sitting in the table.
completed_total="$(psql_payments "SELECT count(*) FROM sagas WHERE status='COMPLETED'")"
failed_total="$(psql_payments "SELECT count(*) FROM sagas WHERE status IN ('FAILED','COMPENSATED')")"
completed=$((completed_total - completed_before))
failed=$((failed_total - failed_before))
drain_rate=$(python3 -c "print(f'{$completed / max($drain_seconds,1):.1f}')")
say "        accepted=$accepted completed=$completed failed/compensated=$failed in ${drain_seconds}s"

step "Correctness under load"
zero_sum="$(psql_ledger "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END),0) FROM entries")"
assert_eq "0" "$zero_sum" "the ledger nets to zero"
# Deliberately compared as TOTALS, not deltas: across the whole life of the
# ledger there must be exactly one CAPTURE transaction per completed saga. A
# delta comparison would miss a duplicate charge attributable to an earlier run.
captures="$(psql_ledger "SELECT count(*) FROM transactions WHERE type='CAPTURE'")"
assert_eq "$completed_total" "$captures" "one capture per completed payment, no duplicates (all time)"
active_holds="$(psql_ledger "SELECT count(*) FROM holds WHERE status='ACTIVE' AND expires_at > now()")"
assert_eq "0" "$active_holds" "no hold left reserving funds"
assert_reconciliation_clean "ledger" "$LEDGER_URL"
assert_reconciliation_clean "payments" "$PAYMENTS_URL"

entries_after="$(psql_ledger "SELECT count(*) FROM entries")"

if [ "$WRITE_REPORT" -eq 1 ]; then
    step "Writing report.md"
    python3 "$LOAD_DIR/render-report.py" \
        --summary "$LOAD_DIR/summary.json" \
        --out "$LOAD_DIR/report.md" \
        --rate "$RATE" --duration "$DURATION" \
        --payers "$PAYER_COUNT" --amount "$AMOUNT_MINOR" \
        --accepted "$accepted" --completed "$completed" --failed "$failed" \
        --drain-seconds "$drain_seconds" --drain-rate "$drain_rate" \
        --entries "$((entries_after - entries_before))" \
        --failures "$FAILURES"
    ok "load/report.md written"
fi

summary
