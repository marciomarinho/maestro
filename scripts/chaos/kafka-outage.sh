#!/usr/bin/env bash
#
# Chaos experiment 2: the broker disappears.
#
# HYPOTHESIS — with Kafka unreachable, payment creation KEEPS SUCCEEDING: the merchant
# gets a 202 because accepting the payment and publishing the command are decoupled by
# the outbox (ADR-0004). Commands queue as unpublished rows (pending grows, oldest row
# ages), payments hold in AUTHORIZING, and nothing is lost. When the broker returns,
# the backlog drains without intervention and every queued payment completes.
#
# Published with its result in docs/chaos/02-kafka-outage.md.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

OUTAGE_PAYMENTS=${OUTAGE_PAYMENTS:-20}

bold "Chaos: Kafka unreachable while payments keep arriving"
assert_proxies_clean

bold "1. Baseline: one payment completes end to end"
read -r code _ id <<< "$(create_payment)"
[ "$code" = "202" ] || fail "baseline payment refused (HTTP $code)"
for _ in $(seq 1 30); do
  [ "$(payment_status "$id")" = "CAPTURED" ] && break
  sleep 1
done
[ "$(payment_status "$id")" = "CAPTURED" ] || fail "baseline payment did not complete"
ok "baseline payment captured"

bold "2. Sever the broker (disable the kafka proxy)"
set_proxy_enabled kafka false
trap 'set_proxy_enabled kafka true 2>/dev/null || true' EXIT
sleep 2

bold "3. During the outage: $OUTAGE_PAYMENTS payments"
IDS=()
START=$(date +%s)
for _ in $(seq 1 "$OUTAGE_PAYMENTS"); do
  read -r code _ id <<< "$(create_payment)"
  [ "$code" = "202" ] || fail "payment refused during broker outage (HTTP $code) — the outbox should absorb this"
  IDS+=("$id")
done
ok "all $OUTAGE_PAYMENTS payments accepted with the broker down"

# The gauge samples on a 10s timer, so give it a moment to notice the backlog.
PENDING=0
for _ in $(seq 1 15); do
  PENDING=$(read_metric "$API" maestro.outbox.pending)
  awk "BEGIN{exit !($PENDING >= $OUTAGE_PAYMENTS)}" && break
  sleep 2
done
OLDEST=$(read_metric "$API" maestro.outbox.oldest.age)
observe "outbox holding the line: pending=$PENDING oldest-age=${OLDEST}s"
awk "BEGIN{exit !($PENDING >= $OUTAGE_PAYMENTS)}" \
  || fail "expected at least $OUTAGE_PAYMENTS unpublished rows, saw $PENDING"
STATUS=$(payment_status "${IDS[0]}")
[ "$STATUS" = "AUTHORIZING" ] || fail "expected AUTHORIZING during outage, saw $STATUS"
ok "payments parked in AUTHORIZING, commands parked in the outbox"

bold "4. Restore the broker and watch the backlog drain"
set_proxy_enabled kafka true
trap - EXIT
RESTORED=$(date +%s)

DEADLINE=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  DONE=0
  for id in "${IDS[@]}"; do
    [ "$(payment_status "$id")" = "CAPTURED" ] && DONE=$((DONE + 1))
  done
  [ "$DONE" = "$OUTAGE_PAYMENTS" ] && break
  sleep 2
done
[ "$DONE" = "$OUTAGE_PAYMENTS" ] || fail "only $DONE/$OUTAGE_PAYMENTS payments completed after recovery"
DRAIN=$(( $(date +%s) - RESTORED ))
# Wait out the gauge's 10s sampling interval so the closing number is the real one.
for _ in $(seq 1 10); do
  PENDING=$(read_metric "$API" maestro.outbox.pending)
  awk "BEGIN{exit !($PENDING == 0)}" && break
  sleep 2
done
observe "backlog drained in ${DRAIN}s; outbox pending=$PENDING"

ok "hypothesis held: zero refusals during the outage, zero losses, full drain ${DRAIN}s after recovery"
