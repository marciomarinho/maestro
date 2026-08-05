#!/usr/bin/env bash
#
# Takes a payment end to end and proves it was taken exactly once.
#
# Run after `docker compose -f deploy/compose/docker-compose.yml up -d`.
# A CI job executes this script, so the README quickstart cannot silently stop working.

set -euo pipefail

# Pick up any host-port overrides, so the script follows the stack that is actually
# running rather than assuming the defaults.
ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy/compose/.env"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

API="${MAESTRO_API:-http://localhost:${PAYMENT_API_PORT:-8080}}"
KEY="${MAESTRO_API_KEY:-sk_test_maestro_demo_0001}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim() { printf '\033[2m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$1"; }

require() { command -v "$1" >/dev/null || fail "$1 is required"; }
require curl
require jq

api() {
  local method=$1 path=$2 idempotency=$3
  shift 3
  curl -sS -X "$method" "$API$path" \
    -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: $idempotency" \
    -H "Content-Type: application/json" \
    "$@"
}

bold "1. Waiting for payment-api"
for _ in $(seq 1 60); do
  if curl -sf "$API/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf "$API/actuator/health" >/dev/null || fail "payment-api did not become healthy"
ok "payment-api is up"

bold "2. Creating and confirming a payment"
IDEMPOTENCY_KEY="demo-$(date +%s)-$RANDOM"
RESPONSE=$(api POST /v1/payments "$IDEMPOTENCY_KEY" -d '{
  "amount_minor": 1999,
  "currency": "AUD",
  "card_token": "tok_visa_4242",
  "reference": "order-10432",
  "confirm": true,
  "metadata": { "channel": "demo" }
}')
echo "$RESPONSE" | jq .
PAYMENT_ID=$(echo "$RESPONSE" | jq -r .id)
[ "$PAYMENT_ID" != "null" ] || fail "no payment id returned"
ok "created $PAYMENT_ID"

bold "3. Waiting for the acquirer"
dim "   payment-api -> outbox -> Kafka -> router -> acquirer-sim -> Kafka -> payment-api"
dim "   capture_method defaults to AUTOMATIC, so it authorizes and then captures"
STATUS=""
for _ in $(seq 1 30); do
  STATUS=$(curl -sS "$API/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $KEY" | jq -r .status)
  [ "$STATUS" = "CAPTURED" ] && break
  sleep 1
done
[ "$STATUS" = "CAPTURED" ] || fail "expected CAPTURED, got $STATUS"
curl -sS "$API/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $KEY" | jq .
ok "authorized and captured"

bold "4. Replaying the same request with the same idempotency key"
REPLAY_HEADERS=$(mktemp)
REPLAY=$(api POST /v1/payments "$IDEMPOTENCY_KEY" -D "$REPLAY_HEADERS" -d '{
  "amount_minor": 1999,
  "currency": "AUD",
  "card_token": "tok_visa_4242",
  "reference": "order-10432",
  "confirm": true,
  "metadata": { "channel": "demo" }
}')
REPLAY_ID=$(echo "$REPLAY" | jq -r .id)
grep -qi 'idempotency-replayed: true' "$REPLAY_HEADERS" || fail "replay was not marked as replayed"
[ "$REPLAY_ID" = "$PAYMENT_ID" ] || fail "replay created a second payment: $REPLAY_ID"
rm -f "$REPLAY_HEADERS"
ok "same payment returned, no second authorization"

bold "5. Reusing the key with a different body"
CONFLICT=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$API/v1/payments" \
  -H "Authorization: Bearer $KEY" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "Content-Type: application/json" \
  -d '{"amount_minor": 9999, "currency": "AUD", "card_token": "tok_visa_4242", "confirm": true}')
[ "$CONFLICT" = "409" ] || fail "expected 409 for key reuse, got $CONFLICT"
ok "rejected with 409 rather than silently returning the first response"

bold "6. Requesting without a credential"
UNAUTHORIZED=$(curl -sS -o /dev/null -w '%{http_code}' "$API/v1/payments/$PAYMENT_ID")
[ "$UNAUTHORIZED" = "401" ] || fail "expected 401 without a credential, got $UNAUTHORIZED"
ok "rejected with 401"

printf '\n'
bold "Payment $PAYMENT_ID captured exactly once."
