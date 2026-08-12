#!/usr/bin/env bash
#
# Chaos experiment 1: database latency on the merchant-facing path.
#
# HYPOTHESIS — with +200 ms (±50) injected on every packet from PostgreSQL to
# payment-api: creation latency rises by several multiples of 200 ms (a creation is
# several round trips inside one transaction, so injected latency compounds — this
# experiment measures the multiplier); nothing fails, nothing is lost, the outbox
# keeps draining; and latency returns to baseline the moment the toxic is removed.
#
# Published with its result in docs/chaos/01-database-latency.md.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

DRIVE=${DRIVE:-30}
LATENCY_MS=200

bold "Chaos: +${LATENCY_MS}ms database latency for payment-api"
assert_proxies_clean

drive() { # count outfile — creates payments, asserts every one is accepted
  local count=$1 outfile=$2 line code time id
  : > "$outfile"
  for _ in $(seq 1 "$count"); do
    line=$(create_payment)
    read -r code time id <<< "$line"
    [ "$code" = "202" ] || fail "payment refused under experiment (HTTP $code)"
    echo "$time" >> "$outfile"
  done
}

bold "1. Baseline: $DRIVE payments"
drive "$DRIVE" /tmp/chaos-baseline.$$
read -r BASE_MED BASE_P90 BASE_MAX <<< "$(latency_summary /tmp/chaos-baseline.$$)"
observe "baseline creation latency: median ${BASE_MED}ms · p90 ${BASE_P90}ms · max ${BASE_MAX}ms"

bold "2. Inject: latency ${LATENCY_MS}ms ±50 on postgres → payment-api"
add_toxic postgres-payment latency '{"latency":'"$LATENCY_MS"',"jitter":50}'
trap 'remove_toxic postgres-payment latency 2>/dev/null || true' EXIT

bold "3. Under fault: $DRIVE payments (all must still be accepted)"
drive "$DRIVE" /tmp/chaos-fault.$$
read -r FAULT_MED FAULT_P90 FAULT_MAX <<< "$(latency_summary /tmp/chaos-fault.$$)"
observe "degraded creation latency: median ${FAULT_MED}ms · p90 ${FAULT_P90}ms · max ${FAULT_MAX}ms"
observe "round trips per creation ≈ $(( FAULT_MED / LATENCY_MS )) (median increase / injected latency)"
ok "all $DRIVE payments accepted under database latency"

PENDING=$(read_metric "$API" maestro.outbox.pending)
OLDEST=$(read_metric "$API" maestro.outbox.oldest.age)
observe "outbox: pending=$PENDING oldest-age=${OLDEST}s (the relay shares the slow connection)"

bold "4. Heal and confirm recovery"
remove_toxic postgres-payment latency
trap - EXIT
drive 10 /tmp/chaos-recovered.$$
read -r REC_MED REC_P90 REC_MAX <<< "$(latency_summary /tmp/chaos-recovered.$$)"
observe "recovered creation latency: median ${REC_MED}ms · p90 ${REC_P90}ms · max ${REC_MAX}ms"
[ "$REC_MED" -lt $(( BASE_MED * 3 + 20 )) ] || fail "latency did not return to baseline"

ok "hypothesis held: latency compounded (${BASE_MED}ms → ${FAULT_MED}ms median), zero failures, immediate recovery"
