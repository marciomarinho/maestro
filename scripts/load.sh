#!/usr/bin/env bash
#
# Runs a k6 load scenario against the composed stack, from inside its network.
#
#   ./scripts/load.sh steady|spike|brownout [extra k6 args...]
#
# k6 runs as a container on the compose network, so the scenario sees the same
# addresses the services see and the host's port overrides are irrelevant. Tunables
# pass through as environment variables: RATE, DURATION, PEAK_RATE, VICTIM.
#
# With the observability overlay up, k6 also streams its metrics into Prometheus
# (remote write), so a load run and the platform's own telemetry land on the same
# dashboards with the same timestamps — which is what makes the load report's graphs
# tell one story instead of two.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.1.0}"
NETWORK="${MAESTRO_NETWORK:-maestro_default}"

SCENARIO="${1:-}"
[ -z "$SCENARIO" ] && { echo "usage: $0 steady|spike|brownout [k6 args...]" >&2; exit 2; }
shift
[ -f "$REPO/deploy/k6/$SCENARIO.js" ] || { echo "unknown scenario: $SCENARIO" >&2; exit 2; }

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
docker network inspect "$NETWORK" >/dev/null 2>&1 \
  || { echo "network $NETWORK not found — is the compose stack up?" >&2; exit 1; }

# Stream into Prometheus only if the overlay's Prometheus is actually there.
OUTPUT_ARGS=()
if docker ps --format '{{.Names}}' | grep -q '^maestro-prometheus-'; then
  OUTPUT_ARGS=(-o experimental-prometheus-rw
    -e K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write
    -e "K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),max")
fi

exec docker run --rm -i \
  --network "$NETWORK" \
  -v "$REPO/deploy/k6:/scripts:ro" \
  -e RUN_ID="$(date +%s)" \
  -e RATE -e DURATION -e BASE_RATE -e PEAK_RATE -e VICTIM \
  "$K6_IMAGE" run "${OUTPUT_ARGS[@]}" "$@" "/scripts/$SCENARIO.js"
