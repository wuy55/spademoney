import http from 'k6/http';
import { check } from 'k6';

// Load profile for POST /payments -- the accept path.
//
// What this measures, and what it deliberately does not: this is the latency and
// throughput a CLIENT sees. POST /payments writes a saga row and returns 202; it
// does not wait for the money to move. That is the published contract (ADR-0022),
// not a shortcut taken to make the numbers look good, and conflating the two
// would be the easiest way to publish a misleading figure here. End-to-end saga
// drain rate is measured separately by run.sh, after the arrival phase ends, and
// both numbers are reported.
//
// constant-arrival-rate, not constant-VUs: an open model holds the offered load
// steady regardless of how the system responds. A closed model (fixed VUs looping)
// silently throttles itself when the system slows down, which turns a latency
// problem into a throughput drop and hides both.

const BASE = __ENV.PAYMENTS_URL || 'http://payments:8081';
const PAYERS = (__ENV.PAYER_IDS || '2').split(',').map(Number);
const PAYEE = Number(__ENV.PAYEE_ID || 3);
const AMOUNT = Number(__ENV.AMOUNT_MINOR || 1);

export const options = {
    // p99 is NOT computed by default -- k6's default trend stats stop at p(95),
    // and a missing percentile reads back as undefined, which formats as a
    // confident "0.0 ms". The first run of this script published exactly that.
    // The plan asks for p50/p99, so p99 has to be asked for.
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        accept: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 50),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: 50,
            maxVUs: 300,
        },
    },
    // Thresholds are assertions, not decoration: a run that breaches them exits
    // non-zero, so a regression fails the script rather than quietly producing a
    // worse report that nobody diffs.
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{expected_response:true}': ['p(99)<1000'],
        checks: ['rate>0.99'],
    },
};

export default function () {
    // Spread across payers. A single payer would serialize on two row locks --
    // the ledger account and the spending-cap row -- and would measure lock
    // contention rather than system throughput. Both are worth knowing; the
    // report states the single-payer case separately rather than burying it here.
    const payer = PAYERS[Math.floor(Math.random() * PAYERS.length)];

    // Unique per iteration. Reusing a key would make every request after the
    // first a replay returning the same saga, which would produce a beautiful
    // throughput number measuring nothing.
    const key = `load-${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(
        `${BASE}/payments`,
        JSON.stringify({
            payerAccountId: payer,
            payeeAccountId: PAYEE,
            amountMinor: AMOUNT,
            currency: 'USD',
        }),
        { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key } },
    );

    check(res, { 'accepted 202': (r) => r.status === 202 });
}

export function handleSummary(data) {
    return {
        '/load/summary.json': JSON.stringify(data, null, 2),
        stdout: textSummary(data),
    };
}

function textSummary(data) {
    const m = data.metrics;
    const dur = m.http_req_duration ? m.http_req_duration.values : {};
    const reqs = m.http_reqs ? m.http_reqs.values : {};
    const failed = m.http_req_failed ? m.http_req_failed.values : {};
    return [
        '',
        `  requests      ${reqs.count || 0}`,
        `  throughput    ${(reqs.rate || 0).toFixed(1)} req/s`,
        `  p50           ${(dur.med || 0).toFixed(1)} ms`,
        `  p95           ${(dur['p(95)'] || 0).toFixed(1)} ms`,
        `  p99           ${(dur['p(99)'] || 0).toFixed(1)} ms`,
        `  max           ${(dur.max || 0).toFixed(1)} ms`,
        `  error rate    ${((failed.rate || 0) * 100).toFixed(3)} %`,
        '',
    ].join('\n');
}
