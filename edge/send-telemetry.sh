#!/usr/bin/env bash
# Sends telemetry to BAS Intelligence the same way the Mako Server edge node does.
# Useful for testing ingestion without Mako Server installed.
#
#   ./edge/send-telemetry.sh CONV-04            one healthy reading
#   ./edge/send-telemetry.sh CONV-04 overheat   a reading above the temperature limit
set -euo pipefail

MACHINE="${1:?usage: send-telemetry.sh MACHINE_CODE [healthy|overheat|vibration]}"
MODE="${2:-healthy}"
URL="${BAS_URL:-http://localhost:8080/api/edge/telemetry}"
KEY="${BAS_EDGE_API_KEY:-demo-edge-key-change-me}"

case "$MODE" in
  healthy)   T=40; V=1.2 ;;
  overheat)  T=78; V=1.3 ;;
  vibration) T=44; V=4.2 ;;
  *) echo "unknown mode: $MODE" >&2; exit 1 ;;
esac

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BODY=$(printf '{"gatewayId":"cli-edge","readings":[{"machineCode":"%s","timestamp":"%s","temperature":%s,"vibration":%s,"rpm":1450,"torque":25,"pressure":5.0}]}' \
  "$MACHINE" "$TS" "$T" "$V")

curl -sS -X POST "$URL" -H "Content-Type: application/json" -H "X-API-Key: $KEY" -d "$BODY" -w '\nHTTP %{http_code}\n'
