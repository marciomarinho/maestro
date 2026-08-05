#!/usr/bin/env bash
#
# Takes a payment through its whole life and shows the books at every step.
#
# Run after `docker compose -f deploy/compose/docker-compose.yml up -d --wait`.
# A CI job executes this script, so what it claims cannot quietly stop being true.

set -euo pipefail

ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy/compose/.env"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

API="${MAESTRO_API:-http://localhost:${PAYMENT_API_PORT:-8080}}"
LEDGER="${MAESTRO_LEDGER:-http://localhost:${LEDGER_PORT:-8083}}"
KEY="${MAESTRO_API_KEY:-sk_test_maestro_demo_0001}"
OPS="${LEDGER_OPS_TOKEN:-ops_local_token}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim() { printf '\033[2m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$1"; }

command -v jq >/dev/null || fail "jq is required"

api() {
  local method=$1 path=$2 key=$3
  shift 3
  curl -sS -X "$method" "$API$path" \
    -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: $key" \
    -H "Content-Type: application/json" "$@"
}

ledger() { curl -sS "$LEDGER$1" -H "Authorization: Bearer $OPS"; }

payment_status() {
  curl -sS "$API/v1/payments/$1" -H "Authorization: Bearer $KEY" | jq -r .status
}

await_status() {
  local id=$1 want=$2
  for _ in $(seq 1 40); do
    [ "$(payment_status "$id")" = "$want" ] && return 0
    sleep 1
  done
  fail "payment $id never reached $want (currently $(payment_status "$id"))"
}

# Renders the postings of every journal transaction for a payment, and proves each sums
# to zero. Debits are positive, credits negative — the double-entry convention.
show_postings() {
  ledger "/ops/ledger/payments/$1" | jq -r '
    .transactions[] |
    "  \(.type) (\(.source_event_id)):",
    (.postings[] | "    \(if .direction == "DEBIT" then "DR" else "CR" end)  \(.account_id)  \(.amount_minor)"),
    "    balance check: \(reduce .postings[] as $p (0; . + (if $p.direction == "DEBIT" then $p.amount_minor else -$p.amount_minor end)))"
  '
}

STAMP="$(date +%s)-$RANDOM"

bold "1. Taking a \$19.99 payment (automatic capture)"
PAYMENT=$(api POST /v1/payments "cap-$STAMP" -d '{
  "amount_minor": 1999, "currency": "AUD", "card_token": "tok_visa_4242",
  "reference": "order-ledger-demo", "capture_method": "AUTOMATIC", "confirm": true
}')
PAYMENT_ID=$(echo "$PAYMENT" | jq -r .id)
[ "$PAYMENT_ID" != "null" ] || fail "no payment id: $PAYMENT"
await_status "$PAYMENT_ID" CAPTURED
ok "$PAYMENT_ID reached CAPTURED"

bold "2. The postings it produced"
dim "   175 bps + 30c on 1999 = 65c fee, so the merchant is owed 1934c"
sleep 2
show_postings "$PAYMENT_ID"
CAPTURE_LINES=$(ledger "/ops/ledger/payments/$PAYMENT_ID" | jq '[.transactions[] | select(.type == "CAPTURE")] | length')
[ "$CAPTURE_LINES" = "1" ] || fail "expected exactly one CAPTURE transaction, got $CAPTURE_LINES"
ok "capture recorded, debits equal credits"

bold "3. Refunding \$5.00 of it"
REFUND=$(api POST "/v1/payments/$PAYMENT_ID/refunds" "ref-$STAMP" -d '{"amount_minor": 500, "reason": "partial return"}')
REFUND_ID=$(echo "$REFUND" | jq -r .id)
[ "$REFUND_ID" != "null" ] || fail "no refund id: $REFUND"
await_status "$PAYMENT_ID" PARTIALLY_REFUNDED
ok "$REFUND_ID settled; payment is PARTIALLY_REFUNDED"

bold "4. The books after the refund"
dim "   the fee is returned in proportion, so a full refund would net to zero"
sleep 2
show_postings "$PAYMENT_ID"

bold "5. Refunding more than remains"
OVER=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$API/v1/payments/$PAYMENT_ID/refunds" \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: over-$STAMP" \
  -H 'Content-Type: application/json' -d '{"amount_minor": 5000}')
[ "$OVER" = "422" ] || fail "expected 422 when over-refunding, got $OVER"
ok "rejected with 422; refunded can never exceed captured"

bold "6. An authorization that is voided instead of captured"
VOIDABLE=$(api POST /v1/payments "void-$STAMP" -d '{
  "amount_minor": 2500, "currency": "AUD", "card_token": "tok_visa_4242",
  "capture_method": "MANUAL", "confirm": true
}')
VOID_ID=$(echo "$VOIDABLE" | jq -r .id)
await_status "$VOID_ID" AUTHORIZED
api POST "/v1/payments/$VOID_ID/void" "voidreq-$STAMP" >/dev/null
await_status "$VOID_ID" VOIDED
HOLD=$(ledger "/ops/ledger/payments/$VOID_ID" | jq -r '.hold.status')
POSTINGS=$(ledger "/ops/ledger/payments/$VOID_ID" | jq '[.transactions[]] | length')
[ "$HOLD" = "RELEASED" ] || fail "expected the hold to be RELEASED, got $HOLD"
[ "$POSTINGS" = "0" ] || fail "a voided authorization must produce no postings, got $POSTINGS"
ok "hold released, and no postings were made — no money ever moved"

bold "7. Verifying the whole ledger"
VERIFY=$(curl -sS -X POST "$LEDGER/ops/ledger/verify" -H "Authorization: Bearer $OPS")
echo "$VERIFY" | jq .
DRIFTS=$(echo "$VERIFY" | jq '.drifts | length')
IMBALANCES=$(echo "$VERIFY" | jq '.currency_imbalances | length')
[ "$DRIFTS" = "0" ] || fail "$DRIFTS account(s) drifted from their postings"
[ "$IMBALANCES" = "0" ] || fail "$IMBALANCES currency imbalance(s)"
ok "every balance matches its postings; all postings sum to zero"

bold "8. Balances"
ledger "/ops/ledger/balances" | jq -r '.[] | "  \(.account_id)  \(.balance_minor)  (\(.posting_count) postings)"'

printf '\n'
bold "The books balance."
