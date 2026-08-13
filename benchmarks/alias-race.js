// Fires the same custom alias from many VUs at once, to exercise the window between
// "is this alias free?" and the INSERT.
//
// Iterations are bucketed: every GROUP consecutive iterations ask for the same alias,
// and with VUS >= GROUP those requests are in flight together. Exactly one of them can
// win; what matters is what the losers are told.
//
//   201 + 409 only  -> the conflict is being reported as a conflict
//   any 500         -> the constraint violation escaped as an unhandled error
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const GROUP = Number(__ENV.GROUP || 25);
const RUN = __ENV.RUN || `${Date.now()}`;
const BASE = __ENV.BASE_URL || 'http://localhost:8080';

const created = new Counter('alias_201_created');
const conflict = new Counter('alias_409_conflict');
const serverError = new Counter('alias_500_server_error');
const other = new Counter('alias_other_status');

export const options = {
  scenarios: {
    race: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 50),
      iterations: Number(__ENV.ITERATIONS || 1000),
      maxDuration: '5m',
    },
  },
  discardResponseBodies: true,
};

export default function () {
  const bucket = Math.floor(exec.scenario.iterationInTest / GROUP);
  const alias = `race-${RUN}-${bucket}`;

  const res = http.post(
    `${BASE}/api/urls`,
    JSON.stringify({ originalUrl: 'https://example.com/race', customAlias: alias }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status === 201) created.add(1);
  else if (res.status === 409) conflict.add(1);
  else if (res.status >= 500) serverError.add(1);
  else other.add(1);
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const total = count('alias_201_created') + count('alias_409_conflict')
    + count('alias_500_server_error') + count('alias_other_status');

  const text = [
    '',
    `aliases contested        ${Math.ceil(Number(__ENV.ITERATIONS || 1000) / GROUP)}`
      + ` (${GROUP} concurrent attempts each)`,
    `requests                 ${total}`,
    `201 Created              ${count('alias_201_created')}`,
    `409 Conflict             ${count('alias_409_conflict')}`,
    `500 Server Error         ${count('alias_500_server_error')}`,
    `other status             ${count('alias_other_status')}`,
    '',
  ].join('\n');

  const out = { stdout: text };
  if (__ENV.OUT) {
    out[`/out/${__ENV.OUT}.json`] = JSON.stringify(data, null, 2);
    out[`/out/${__ENV.OUT}.txt`] = text;
  }
  return out;
}
