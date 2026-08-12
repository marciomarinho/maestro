// Shared plumbing for the load scenarios: one place that knows how to create a
// payment the way a merchant would — idempotency key, API key, realistic amounts.

import http from 'k6/http';
import { check } from 'k6';

export const API = __ENV.BASE_URL || 'http://payment-api:8080';
export const SIM = __ENV.SIM_URL || 'http://acquirer-sim:8082';
export const API_KEY = __ENV.API_KEY || 'sk_test_maestro_demo_0001';

// Unique per test run, so a re-run can never be absorbed by yesterday's
// idempotency records and report a suspiciously fast, entirely fake p99.
const RUN = __ENV.RUN_ID || `${Date.now()}`;

export function createPayment(tagName) {
  const amount = 500 + Math.floor(Math.random() * 19500); // $5.00 – $200.00
  const response = http.post(
    `${API}/v1/payments`,
    JSON.stringify({
      amount_minor: amount,
      currency: 'AUD',
      card_token: 'tok_visa_4242',
      reference: `load-${RUN}-${__VU}-${__ITER}`,
      confirm: true,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${API_KEY}`,
        'Idempotency-Key': `load-${RUN}-${__VU}-${__ITER}`,
      },
      tags: { name: tagName },
    },
  );
  check(response, { 'payment accepted (202)': (r) => r.status === 202 });
  return response;
}

export function setAcquirerMode(acquirerId, mode) {
  // brownout | blackout | heal — the acquirer-sim fault-injection API.
  const response = http.post(`${SIM}/admin/acquirers/${acquirerId}/${mode}`, null, {
    tags: { name: `sim-${mode}` },
  });
  check(response, { [`${mode} ${acquirerId} accepted`]: (r) => r.status < 300 });
}
