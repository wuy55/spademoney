#!/usr/bin/env bash
# Shared helpers for the smoke and chaos scripts.
#
# Requirements are deliberately minimal: docker compose, curl, and python3.
# jq is NOT required -- it is the one tool most likely to be missing on a
# reviewer's machine, and "install jq first" is a bad first line for a script
# whose whole job is to be run by someone else on a laptop.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEDGER_URL="${LEDGER_URL:-http://localhost:8080}"
PAYMENTS_URL="${PAYMENTS_URL:-http://localhost:8081}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

FAILURES=0

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$BOLD" "$*" "$OFF"; }
ok()   { printf '  %sPASS%s  %s\n' "$GREEN" "$OFF" "$*"; }
bad()  { printf '  %sFAIL%s  %s\n' "$RED" "$OFF" "$*"; FAILURES=$((FAILURES + 1)); }
warn() { printf '  %s..%s    %s\n' "$YELLOW" "$OFF" "$*"; }

compose() { (cd "$REPO_ROOT" && docker compose "$@"); }

# Run SQL against one of the two databases and print the raw result.
# -tA strips headers and alignment, so the output is a value a script can use.
psql_ledger()   { compose exec -T postgres psql -U spademoney -d ledger   -tA -c "$1"; }
psql_payments() { compose exec -T postgres psql -U spademoney -d payments -tA -c "$1"; }

psql_ledger_file()   { compose exec -T postgres psql -U spademoney -d ledger   -q -f - < "$1"; }
psql_payments_file() { compose exec -T postgres psql -U spademoney -d payments -q -f - < "$1"; }

# Read one field out of a JSON document on stdin. Nested paths use dots.
# Missing keys print an empty string rather than failing, so a caller can tell
# "absent" from "present but empty" by checking both.
json() {
    python3 -c '
import json, sys
doc = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    if doc is None:
        break
    doc = doc.get(key) if isinstance(doc, dict) else None
print("" if doc is None else doc)
' "$1"
}

# Block until a URL answers 200, or give up.
wait_for_http() {
    local url="$1" timeout="${2:-120}" waited=0
    while ! curl -fsS -o /dev/null "$url" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
        if [ "$waited" -ge "$timeout" ]; then
            bad "timed out after ${timeout}s waiting for $url"
            return 1
        fi
    done
    return 0
}

# Block until compose reports a service healthy.
#
# This waits on the HEALTHCHECK rather than on the port, because a Spring Boot
# process accepts connections some seconds before Flyway has finished and the
# app is actually able to answer. Waiting on the port is the classic way to get
# a flaky script that fails once in twenty runs on a slow machine.
wait_healthy() {
    local service="$1" timeout="${2:-180}" waited=0 state
    while true; do
        state="$(compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null \
                 | awk -v s="$service" '$1 == s { print $2 }')"
        [ "$state" = "healthy" ] && return 0
        sleep 2
        waited=$((waited + 2))
        if [ "$waited" -ge "$timeout" ]; then
            bad "timed out after ${timeout}s waiting for $service to become healthy (last: ${state:-unknown})"
            return 1
        fi
    done
}

# Assert helpers. Every one of them records a failure rather than exiting, so a
# run reports everything that is wrong instead of only the first thing.
assert_eq() {
    local expected="$1" actual="$2" what="$3"
    if [ "$expected" = "$actual" ]; then
        ok "$what ($actual)"
    else
        bad "$what: expected $expected, got $actual"
    fi
}

assert_reconciliation_clean() {
    local name="$1" url="$2" report healthy
    report="$(curl -fsS "$url/reconciliation")" || { bad "$name reconciliation did not answer"; return; }

    healthy="$(printf '%s' "$report" | json healthy)"
    if [ "$healthy" = "True" ] || [ "$healthy" = "true" ]; then
        ok "$name reconciliation clean ($(printf '%s' "$report" | python3 -c '
import json,sys
print(len(json.load(sys.stdin)["checks"]))') checks)"
    else
        bad "$name reconciliation reported problems:"
        printf '%s' "$report" | python3 -c '
import json, sys
for check in json.load(sys.stdin)["checks"]:
    if not check["passed"]:
        print("        - %s: %s" % (check["name"], check["detail"]))'
    fi
}

summary() {
    printf '\n'
    if [ "$FAILURES" -eq 0 ]; then
        printf '%s%sALL CHECKS PASSED%s\n' "$BOLD" "$GREEN" "$OFF"
        return 0
    fi
    printf '%s%s%d CHECK(S) FAILED%s\n' "$BOLD" "$RED" "$FAILURES" "$OFF"
    return 1
}
