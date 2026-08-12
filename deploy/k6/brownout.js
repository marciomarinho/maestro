// Brownout under load: the flagship scenario, at volume.
//
// demo-brownout.sh tells this story interactively at demo scale; this runs it at load
// and holds the platform to numbers. Steady traffic flows while the acquirer carrying
// the corridor's majority browns out — slow, mostly failing, still passing health
// checks — and later heals. The router must shift traffic, cascade the failures it
// catches mid-flight, and keep the merchant-visible request surface effectively
// unaffected throughout.
//
//   ./scripts/load.sh brownout          # 15 rps for 5m; brownout at 60s, heal at 180s

import { sleep } from 'k6';
import { createPayment, setAcquirerMode } from './lib.js';

const RATE = Number(__ENV.RATE || 15);
const VICTIM = __ENV.VICTIM || 'southcross';

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 100,
      maxVUs: 300,
      exec: 'load',
    },
    fault: {
      // One VU, one iteration: the hand on the dial.
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '5m',
      exec: 'fault',
    },
  },
  thresholds: {
    // The success-rate floor, as a threshold: the merchant keeps getting 202s while
    // an acquirer carrying most of the corridor quietly fails. Cascading failover is
    // what makes this hold; the request only fails if every candidate does.
    'http_req_failed{name:create}': ['rate<0.02'],
    'checks{scenario:load}': ['rate>0.98'],
    'http_req_duration{name:create}': ['p(95)<2000'],
  },
};

export function load() {
  createPayment('create');
}

export function fault() {
  sleep(60);                            // minute one: healthy baseline
  setAcquirerMode(VICTIM, 'brownout');  // minutes two and three: the bad afternoon
  sleep(120);
  setAcquirerMode(VICTIM, 'heal');      // minutes four and five: recovery on exploration traffic
}

export function teardown() {
  // The victim is healed even if the run is aborted mid-brownout, so a failed load
  // test cannot leave the shared stack degraded for whoever runs the next demo.
  setAcquirerMode(VICTIM, 'heal');
}
