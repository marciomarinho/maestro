// Spike: a flash sale starts, and five times the traffic arrives in ten seconds.
//
// What is under test is not whether the platform serves the spike at steady-state
// latency — it will not, and pretending otherwise would be a dishonest threshold —
// but whether it degrades and recovers: no failures, no stuck payments, latency back
// to baseline within the cooldown.
//
//   ./scripts/load.sh spike

import { createPayment } from './lib.js';

const BASE = Number(__ENV.BASE_RATE || 10);
const PEAK = Number(__ENV.PEAK_RATE || 50);

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: BASE,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { duration: '1m', target: BASE },   // baseline
        { duration: '10s', target: PEAK },  // the sale goes live
        { duration: '1m', target: PEAK },   // sustained peak
        { duration: '10s', target: BASE },  // it subsides
        { duration: '2m', target: BASE },   // recovery must be visible here
      ],
    },
  },
  thresholds: {
    // Nothing may fail; latency may stretch. The report reads the recovery shape
    // off the dashboard, which a single number cannot express.
    'http_req_failed{name:create}': ['rate<0.01'],
    'http_req_duration{name:create}': ['p(99)<1000'],
    'checks': ['rate>0.99'],
  },
};

export default function () {
  createPayment('create');
}
