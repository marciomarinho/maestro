// Steady state: the platform on an ordinary afternoon.
//
// Constant arrival rate rather than constant VUs, because a merchant's checkout does
// not slow down to match a struggling backend — requests keep arriving at the same
// rate and latency is what gives. This is the scenario the SLO table is stated
// against, so its thresholds are the SLOs.
//
//   ./scripts/load.sh steady            # 20 rps for 5m (defaults)
//   RATE=50 DURATION=10m ./scripts/load.sh steady

import { createPayment } from './lib.js';

const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '5m';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    // The SLO: payment creation p99 under 150 ms. Measured from the client, so it
    // includes one docker-network hop the service-side histogram does not.
    'http_req_duration{name:create}': ['p(99)<150'],
    'http_req_failed{name:create}': ['rate<0.01'],
    'checks': ['rate>0.99'],
  },
};

export default function () {
  createPayment('create');
}
