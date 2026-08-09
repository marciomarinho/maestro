#!/usr/bin/env bash
#
# Breaks an acquirer while the platform is running, and shows the merchant barely notices.
#
# This is the demonstration the project exists for. Everything else — the ledger, the
# idempotency, the outbox — is table stakes that a competent team would build. Routing
# around a degrading bank without a human in the loop is the part worth watching.
#
# Run after `docker compose -f deploy/compose/docker-compose.yml up -d --wait`.
# A CI job executes this script, so the claims in the README cannot silently stop working.

set -euo pipefail

ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy/compose/.env"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

API="${MAESTRO_API:-http://localhost:${PAYMENT_API_PORT:-8080}}"
ROUTER="${MAESTRO_ROUTER:-http://localhost:${ROUTER_PORT:-8081}}"
SIM="${MAESTRO_ACQUIRER_SIM:-http://localhost:${ACQUIRER_SIM_PORT:-8082}}"
KEY="${MAESTRO_API_KEY:-sk_test_maestro_demo_0001}"
OPS="${ROUTER_OPS_TOKEN:-ops_local_token}"

# The acquirer we break. The cheapest one, so it is also the one carrying the traffic —
# breaking the acquirer nobody uses would prove nothing.
VICTIM="${MAESTRO_VICTIM:-southcross}"
CORRIDOR="VISA:AUD"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim() { printf '\033[2m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$1"; }

require() { command -v "$1" >/dev/null || fail "$1 is required"; }
require curl
require jq

# Restore the acquirer however this script exits, so a failed run does not leave a
# broken simulator behind for the next one.
cleanup() { curl -sf -X POST "$SIM/admin/acquirers/$VICTIM/heal" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# --- helpers ----------------------------------------------------------------

# Fires n payments concurrently and reports how many the platform authorized.
drive() {
  local count=$1 label=$2 approved=0 batch=10
  local results
  results=$(
    for ((i = 1; i <= count; i++)); do
      curl -sS -X POST "$API/v1/payments" \
        -H "Authorization: Bearer $KEY" \
        -H "Idempotency-Key: ${label}-${i}-${RANDOM}" \
        -H "Content-Type: application/json" \
        -d '{"amount_minor":1999,"currency":"AUD","card_token":"tok_visa_4242","confirm":true}' \
        -o /dev/null -w '%{http_code}\n' &
      if ((i % batch == 0)); then wait; fi
    done
    wait
  )
  approved=$(echo "$results" | grep -c '^2' || true)
  echo "$approved"
}

routing_table() {
  curl -sS "$ROUTER/ops/routing/corridors/$CORRIDOR" -H "Authorization: Bearer $OPS" \
    | jq -r '.candidates[] | "    \(.acquirer_id | . + "                "[0:12-length])  share \(.probability * 100 | floor)%  score \(.score * 1000 | floor / 1000)  fail-rate \(.technical_failure_rate * 100 | floor)%  breaker \(.breaker)"'
}

# Breaker states, read from the health view rather than the corridor view. An open
# breaker removes the acquirer from the candidate list entirely, so the corridor table
# shows it by omission — which looks like a bug unless something says otherwise.
breakers() {
  curl -sS "$ROUTER/ops/routing/health" -H "Authorization: Bearer $OPS" \
    | jq -r --arg c "$CORRIDOR" '.[] | select(.corridor == $c) |
        "    \(.acquirer_id | . + "                "[0:12-length])  breaker \(.breaker)  samples \(.samples | floor)"'
}

# Share of authorizations the victim received, over the whole run so far.
victim_share() {
  curl -sS "$ROUTER/ops/routing/corridors/$CORRIDOR" -H "Authorization: Bearer $OPS" \
    | jq -r --arg a "$VICTIM" '.candidates[] | select(.acquirer_id == $a) | .probability * 100 | floor'
}

# Drives traffic in batches until the victim's share crosses a threshold, or the budget
# is spent. Polling rather than measuring once, because the quantity being asserted on is
# converging: sampling it the instant the load stops reads the system mid-move and
# compares a settled number against an unsettled one.
await_share() {
  local mode=$1 target=$2 batches=$3 per_batch=$4 label=$5 share=0
  for ((b = 1; b <= batches; b++)); do
    drive "$per_batch" "${label}${b}" >/dev/null
    sleep 2
    share=$(victim_share)
    case "$mode" in
      below) if [ "$share" -lt "$target" ]; then echo "$share"; return 0; fi ;;
      above) if [ "$share" -gt "$target" ]; then echo "$share"; return 0; fi ;;
    esac
  done
  echo "$share"
  return 1
}

# --- 1. steady state --------------------------------------------------------

bold "1. Warming up: 60 payments across three healthy acquirers"
dim "   nothing is wrong, so the router should favour whichever is cheapest"
curl -sf -X POST "$SIM/admin/acquirers/$VICTIM/heal" >/dev/null || fail "acquirer-sim is not reachable"
WARM=$(drive 60 warm)
sleep 3
[ "$WARM" -ge 55 ] || fail "only $WARM/60 payments were accepted while everything was healthy"
ok "$WARM/60 accepted"

# Kept driving until the cheapest acquirer is actually winning. A second run of this
# script starts with the router still remembering the first one — health outlives the
# demo by a few minutes, which is the model working correctly and would otherwise make
# the script fail on a starting state it never established.
BASELINE=$(await_share above 50 8 30 warmup) \
  || fail "$VICTIM never reclaimed the corridor (stuck at ${BASELINE}%); is it still degraded?"
routing_table
ok "$VICTIM is carrying ${BASELINE}% of the corridor"

# --- 2. the brownout --------------------------------------------------------

echo
bold "2. Breaking $VICTIM — a brownout, not an outage"
dim "   it keeps answering, keeps passing its health check, and burns most of what it is given"
dim "   this is the case a static routing table with health-check failover cannot see"
curl -sS -X POST "$SIM/admin/acquirers/$VICTIM/brownout" | jq -c .

bold "3. Driving 120 payments through the degraded corridor"
DEGRADED=$(drive 120 brownout)
sleep 3

routing_table
dim "   breaker state — an OPEN one is cut off entirely and vanishes from the table above:"
breakers

# The number that matters commercially. Failing over on technical failures means the
# merchant's customers mostly never learn that an acquiring bank had a bad afternoon.
RATE=$((DEGRADED * 100 / 120))
dim "   merchant-visible acceptance during the brownout: ${DEGRADED}/120 (${RATE}%)"
[ "$RATE" -ge 95 ] || fail "acceptance fell to ${RATE}%, below the stated floor of 95%"
ok "acceptance held at ${RATE}% while an acquirer was failing most requests"

# --- 3. the audit trail -----------------------------------------------------

echo
bold "4. Why did that payment go where it went?"
dim "   the routing decision is a query, not an investigation"

# Takes payments one at a time until one of them needs a second acquirer, then prints its
# history. Deliberately through the merchant API rather than by reading the router's
# database: the point being demonstrated is that a merchant can answer this themselves.
CASCADED=""
for _ in $(seq 1 20); do
  CANDIDATE=$(curl -sS -X POST "$API/v1/payments" \
    -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: trail-${RANDOM}-${RANDOM}" \
    -H "Content-Type: application/json" \
    -d '{"amount_minor":1999,"currency":"AUD","card_token":"tok_visa_4242","confirm":true}' \
    | jq -r .id)
  # The attempt history is a projection of the router's events, so it lags the payment
  # by the time it takes one Kafka round trip (ADR-0017).
  for _ in 1 2 3; do
    sleep 1
    ATTEMPTS=$(curl -sS "$API/v1/payments/$CANDIDATE/attempts" -H "Authorization: Bearer $KEY")
    [ "$(echo "$ATTEMPTS" | jq 'length')" -gt 0 ] && break
  done
  if [ "$(echo "$ATTEMPTS" | jq '[.[] | select(.operation == "AUTHORIZE")] | length')" -gt 1 ]; then
    CASCADED=$CANDIDATE
    break
  fi
done

if [ -n "$CASCADED" ]; then
  echo "$ATTEMPTS" | jq -r '.[] |
    "    \(.operation) #\(.attempt_no)  \(.acquirer_id)  \(.selection_reason)  score \(.health_score // "n/a")  → \(.outcome)"'
  ok "payment $CASCADED shows the acquirer that failed and the one that rescued it"
else
  dim "   (no payment needed a second acquirer in this run)"
fi

# --- 3b. the rule failover must never break ---------------------------------

echo
bold "5. A decline is the issuer's answer, and it is final everywhere"
dim "   every acquirer set to decline, so whichever is chosen refuses. The payment must"
dim "   be declined once, not shopped around three banks until one says yes (ADR-0012)"
for acquirer in southcross northbank meridian; do
  curl -sS -o /dev/null -X PUT "$SIM/admin/acquirers/$acquirer/behaviour" \
    -H 'Content-Type: application/json' \
    -d '{"latency_ms":30,"latency_jitter_ms":0,"decline_rate":1.0,"technical_failure_rate":0,"timeout_rate":0,"max_in_flight":0}'
done

DECLINED_ID=$(curl -sS -X POST "$API/v1/payments" \
  -H "Authorization: Bearer $KEY" \
  -H "Idempotency-Key: decline-${RANDOM}-${RANDOM}" \
  -H "Content-Type: application/json" \
  -d '{"amount_minor":1999,"currency":"AUD","card_token":"tok_visa_4242","confirm":true}' | jq -r .id)

DECLINE_TRAIL=""
for _ in 1 2 3 4 5; do
  sleep 1
  DECLINE_TRAIL=$(curl -sS "$API/v1/payments/$DECLINED_ID/attempts" -H "Authorization: Bearer $KEY")
  [ "$(echo "$DECLINE_TRAIL" | jq 'length')" -gt 0 ] && break
done

echo "$DECLINE_TRAIL" | jq -r '.[] |
  "    \(.operation) #\(.attempt_no)  \(.acquirer_id)  \(.selection_reason)  → \(.outcome) (\(.response_code))"'

AUTH_TRIES=$(echo "$DECLINE_TRAIL" | jq '[.[] | select(.operation == "AUTHORIZE")] | length')
[ "$AUTH_TRIES" -eq 1 ] || fail "a business decline was re-presented to $AUTH_TRIES acquirers"
STATUS=$(curl -sS "$API/v1/payments/$DECLINED_ID" -H "Authorization: Bearer $KEY" | jq -r .status)
[ "$STATUS" = "DECLINED" ] || fail "expected DECLINED, got $STATUS"
ok "declined once, by one acquirer, and never re-presented"

# Back to the brownout for the rest of the demo.
for acquirer in northbank meridian; do
  curl -sS -o /dev/null -X POST "$SIM/admin/acquirers/$acquirer/heal"
done
curl -sS -o /dev/null -X POST "$SIM/admin/acquirers/$VICTIM/brownout"

# --- 4. the traffic settles -------------------------------------------------

echo
bold "6. Letting the router's opinion settle"
dim "   measured after the move rather than during it: sampling the instant the load stops"
dim "   reads a converging number and compares it against a settled one"
SHARE=$(await_share below 25 6 30 settle) \
  || fail "$VICTIM was still receiving ${SHARE}% after the settling traffic"
routing_table
ok "traffic moved: $VICTIM went from ${BASELINE}% to ${SHARE}% of the corridor"

# --- 5. recovery ------------------------------------------------------------

echo
bold "7. Healing $VICTIM"
dim "   nobody tells the router. It has to notice — and what it notices with is the"
dim "   exploration traffic it never stopped sending (ADR-0007)"
curl -sS -X POST "$SIM/admin/acquirers/$VICTIM/heal" | jq -c .

bold "8. Driving traffic while it recovers"
RECOVERED=$(drive 60 healed)
RECOVERY_TARGET=$((SHARE + 20))
FINAL=$(await_share above "$RECOVERY_TARGET" 8 30 recover) \
  || fail "$VICTIM only climbed back to ${FINAL}%, short of ${RECOVERY_TARGET}%"
routing_table
ok "recovery detected from exploration traffic alone: ${SHARE}% → ${FINAL}%"
dim "   a router that always picked the best score would have sent it nothing, learned"
dim "   nothing, and eventually promoted it back on no evidence at all"

RECOVERED_RATE=$((RECOVERED * 100 / 60))
[ "$RECOVERED_RATE" -ge 95 ] || fail "acceptance after healing was only ${RECOVERED_RATE}%"
ok "acceptance after healing: ${RECOVERED}/60 (${RECOVERED_RATE}%)"

echo
bold "An acquirer failed, recovered, and the merchant barely noticed."
dim "   no configuration was changed, no deployment happened, and nobody was paged."
