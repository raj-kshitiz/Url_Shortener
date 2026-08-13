// Load profile for GET /{shortCode} — the redirect hot path.
//
// redirects: 0 is not a detail: k6 follows 302s by default, which would send every
// iteration out to the real internet and measure that instead of this service.
//
//   docker run --rm --network host -v "$PWD:/out" \
//     -e SHORT_CODE=abc123 -e VUS=50 -e DURATION=60s -e OUT=after \
//     grafana/k6 run /out/redirect.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const CODE = __ENV.SHORT_CODE;

export const options = {
  scenarios: {
    redirect: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '60s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

export default function () {
  const res = http.get(`${BASE}/${CODE}`, { redirects: 0 });
  check(res, { 'status is 302': (r) => r.status === 302 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const t = m.http_req_duration.values;
  const line = (k, v) => `${k.padEnd(24)} ${v}`;
  const text = [
    '',
    line('requests', m.http_reqs.values.count),
    line('throughput (req/s)', m.http_reqs.values.rate.toFixed(1)),
    line('non-302 responses', m.checks ? m.checks.values.fails : 'n/a'),
    line('failed requests', m.http_req_failed.values.passes),
    line('latency avg (ms)', t.avg.toFixed(2)),
    line('latency p50 (ms)', t.med.toFixed(2)),
    line('latency p90 (ms)', t['p(90)'].toFixed(2)),
    line('latency p95 (ms)', t['p(95)'].toFixed(2)),
    line('latency p99 (ms)', t['p(99)'].toFixed(2)),
    line('latency max (ms)', t.max.toFixed(2)),
    '',
  ].join('\n');

  const out = {};
  out.stdout = text;
  if (__ENV.OUT) {
    out[`/out/${__ENV.OUT}.json`] = JSON.stringify(data, null, 2);
    out[`/out/${__ENV.OUT}.txt`] = text;
  }
  return out;
}
