#!/usr/bin/env bash
#
# Shared plumbing for the chaos experiments. Each experiment script states its own
# hypothesis, injects one fault through Toxiproxy, observes, and heals — this file is
# only the hands: creating payments, reading gauges, driving the Toxiproxy API.
#
# Requires the stack up with the chaos overlay:
#   docker compose -f deploy/compose/docker-compose.yml \
#                  -f deploy/compose/docker-compose.chaos.yml up -d --wait

set -euo pipefail

ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/deploy/compose/.env"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

API="${MAESTRO_API:-http://localhost:${PAYMENT_API_PORT:-8080}}"
ROUTER="${MAESTRO_ROUTER:-http://localhost:${ROUTER_PORT:-8081}}"
TOXIPROXY="${MAESTRO_TOXIPROXY:-http://localhost:${TOXIPROXY_PORT:-8474}}"
KEY="${MAESTRO_API_KEY:-sk_test_maestro_demo_0001}"
ROUTER_OPS_TOKEN="${ROUTER_OPS_TOKEN:-ops_local_token}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim() { printf '\033[2m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$1"; }
observe() { printf '\033[36m· %s\033[0m\n' "$1"; }

require() { command -v "$1" >/dev/null || fail "$1 is required"; }
require curl
require jq
require uuidgen

RUN_ID="chaos-$(date +%s)"

# Creates one confirmed payment; echoes "<http_code> <time_seconds> <payment_id>".
# The idempotency key is stateless on purpose: callers run this in $(...) subshells,
# where a counter would silently reset and turn every "new" payment into a replay of
# the first one — which is exactly the kind of bug an idempotent API hides perfectly.
create_payment() {
  local key out
  key="$RUN_ID-$(uuidgen)"
  out=$(curl -sS -m 30 -o /tmp/chaos-body.$$ -w '%{http_code} %{time_total}' \
    -X POST "$API/v1/payments" \
    -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: $key" \
    -H "Content-Type: application/json" \
    -d '{"amount_minor": 1999, "currency": "AUD", "card_token": "tok_visa_4242",
         "reference": "'"$key"'", "confirm": true}') || { echo "000 30.0 -"; return; }
  echo "$out $(jq -r '.id // "-"' /tmp/chaos-body.$$ 2>/dev/null || echo '-')"
}

payment_status() {
  curl -sS "$API/v1/payments/$1" -H "Authorization: Bearer $KEY" | jq -r '.status'
}

# Reads a single-value meter from a service's actuator, e.g.
#   read_metric "$API" maestro.outbox.pending
read_metric() {
  curl -sS "$1/actuator/metrics/$2" 2>/dev/null | jq -r '.measurements[0].value' 2>/dev/null || echo "NaN"
}

# --- Toxiproxy ---------------------------------------------------------------

add_toxic() { # proxy name json-attributes... e.g. add_toxic postgres-payment latency '{"latency":200}'
  local proxy=$1 type=$2 attributes=$3
  curl -sS -X POST "$TOXIPROXY/proxies/$proxy/toxics" \
    -d '{"name":"'"$type"'-experiment","type":"'"$type"'","stream":"downstream","attributes":'"$attributes"'}' \
    | jq -e .name >/dev/null || fail "could not add $type toxic to $proxy"
  ok "toxic applied: $type on $proxy $attributes"
}

remove_toxic() {
  local proxy=$1 type=$2
  curl -sS -X DELETE "$TOXIPROXY/proxies/$proxy/toxics/$type-experiment" >/dev/null
  ok "toxic removed: $type on $proxy"
}

set_proxy_enabled() {
  local proxy=$1 enabled=$2
  curl -sS -X POST "$TOXIPROXY/proxies/$proxy" -d '{"enabled":'"$enabled"'}' \
    | jq -e .name >/dev/null || fail "could not set $proxy enabled=$enabled"
  ok "proxy $proxy enabled=$enabled"
}

# Cleanliness on entry: an experiment must start from an unpoisoned stack.
assert_proxies_clean() {
  local toxics
  toxics=$(curl -sS "$TOXIPROXY/proxies" | jq '[.[].toxics[]] | length')
  [ "$toxics" = "0" ] || fail "proxies already carry $toxics toxic(s) — heal the stack first"
  curl -sS "$TOXIPROXY/proxies" | jq -e 'all(.[]; .enabled)' >/dev/null \
    || fail "a proxy is disabled — heal the stack first"
}

# Simple stats over a file of seconds-durations: prints "median p_high max" (ms).
latency_summary() {
  sort -n "$1" | awk '
    { v[NR] = $1 }
    END {
      if (NR == 0) { print "0 0 0"; exit }
      median = v[int((NR + 1) / 2)]
      p = v[NR - int(NR / 10)]        # ~p90
      printf "%.0f %.0f %.0f", median * 1000, p * 1000, v[NR] * 1000
    }'
}
