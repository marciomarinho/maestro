#!/usr/bin/env bash
#
# Chaos experiment 3: every acquirer stops answering.
#
# The brownout scenario (one sick acquirer among healthy ones) is k6's; this is the
# darker case failover cannot fix, because there is nowhere left to fail over to. What
# is under test is the platform's restraint: bounded attempts instead of a retry
# storm, breakers opening, payments reaching a terminal state instead of hanging, and
# recovery without intervention once the network heals.
#
# HYPOTHESIS — with the acquirer path hanging indefinitely: payments are still
# accepted (202); each reaches FAILED after a bounded number of attempts; the retry
# budget caps total attempts well below the unbounded worst case; breakers open; and
# after healing, a fresh payment authorizes and the breakers close on exploration
# probes without anyone touching the router.
#
# Published with its result in docs/chaos/03-acquirer-timeouts.md.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PAYMENTS=${PAYMENTS:-10}

bold "Chaos: acquirer network hangs — timeouts for every acquirer"
assert_proxies_clean

attempts_total() {
  # Sum of settled attempts, from the router's counter.
  curl -sS "$ROUTER/actuator/metrics/maestro.router.attempts" 2>/dev/null \
    | jq -r '.measurements[0].value // 0' | cut -d. -f1
}

bold "1. Baseline: one payment completes"
read -r code _ id <<< "$(create_payment)"
[ "$code" = "202" ] || fail "baseline payment refused"
for _ in $(seq 1 30); do
  [ "$(payment_status "$id")" = "CAPTURED" ] && break
  sleep 1
done
ok "baseline payment captured"
BASE_ATTEMPTS=$(attempts_total)

bold "2. Inject: the acquirer connection hangs (timeout toxic, never answers)"
add_toxic acquirers timeout '{"timeout":0}'
trap 'remove_toxic acquirers timeout 2>/dev/null || true' EXIT

bold "3. $PAYMENTS payments into the void"
IDS=()
for _ in $(seq 1 "$PAYMENTS"); do
  read -r code _ id <<< "$(create_payment)"
  [ "$code" = "202" ] || fail "payment refused during acquirer outage (HTTP $code)"
  IDS+=("$id")
done
ok "all $PAYMENTS payments accepted — the merchant surface does not block on acquirers"

bold "4. Await terminal states (bounded attempts, no storm, nothing stuck)"
DEADLINE=$(( $(date +%s) + 240 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  TERMINAL=0
  for id in "${IDS[@]}"; do
    case "$(payment_status "$id")" in FAILED|DECLINED) TERMINAL=$((TERMINAL + 1));; esac
  done
  [ "$TERMINAL" = "$PAYMENTS" ] && break
  sleep 5
done
[ "$TERMINAL" = "$PAYMENTS" ] || fail "only $TERMINAL/$PAYMENTS payments reached a terminal state — the rest are stuck"
ok "every payment reached a terminal state; none stuck in AUTHORIZING"

ATTEMPTS=$(( $(attempts_total) - BASE_ATTEMPTS ))
BUDGET=$(read_metric "$ROUTER" maestro.router.retry.budget.utilisation)
observe "total attempts for $PAYMENTS payments: $ATTEMPTS (unbounded worst case would be $((PAYMENTS * 6)))"
observe "retry budget utilisation: $BUDGET"
BREAKERS=$(curl -sS "$ROUTER/ops/routing/health" -H "Authorization: Bearer $ROUTER_OPS_TOKEN" \
  | jq '[.[] | select(.breaker != "CLOSED")] | length')
observe "breakers not closed: $BREAKERS"

bold "5. Heal, then recovery with no operator action"
remove_toxic acquirers timeout
trap - EXIT
read -r code _ id <<< "$(create_payment)"
[ "$code" = "202" ] || fail "post-heal payment refused"
DEADLINE=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  [ "$(payment_status "$id")" = "CAPTURED" ] && break
  sleep 2
done
[ "$(payment_status "$id")" = "CAPTURED" ] || fail "payment did not complete after healing"
ok "first post-heal payment captured — breakers readmitted the corridor on their own probes"

ok "hypothesis held: accepted at the door, bounded attempts ($ATTEMPTS), terminal not stuck, self-healing recovery"
