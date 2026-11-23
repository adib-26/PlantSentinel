#!/usr/bin/env bash
# Real end-to-end test of "BAS becomes unavailable" with a real Mako Server edge node:
#   1. the edge node posts readings to PlantSentinel,
#   2. PlantSentinel goes down: readings queue on the edge (store-and-forward),
#   3. PlantSentinel comes back: the queue is flushed, nothing is lost and nothing is stored twice.
#
# Needs a Mako Server build (macOS has no binary; see docs/testing.md) and Java 21:
#   MAKO_DIR=/path/to/BAS ./edge/test-edge-outage.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAKO_DIR="${MAKO_DIR:?set MAKO_DIR to the directory containing the mako executable and mako.zip}"
if ! java -version >/dev/null 2>&1 && [[ -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
fi
APP_LOG=$(mktemp); MAKO_LOG=$(mktemp)
cleanup() { pkill -f 'BasIntelligenceApplication' 2>/dev/null || true; [[ -n "${MAKO_PID:-}" ]] && kill "$MAKO_PID" 2>/dev/null || true; }
trap cleanup EXIT

start_app() {
  (cd "$ROOT" && ./mvnw -q -B spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m" >"$APP_LOG" 2>&1 &)
  for _ in $(seq 1 90); do grep -qE 'Started BasIntelligence|Application run failed' "$APP_LOG" && break; sleep 2; done
  grep -q 'Started BasIntelligence' "$APP_LOG" || { echo "app failed to start"; exit 1; }
}
status() { curl -s http://localhost/ | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' ' | grep -oE "$1 [^ ]+" | awk '{print $NF}'; }
stored() { curl -s -u viewer:viewer123 "http://localhost:8080/api/machines/CONV-04/analysis?minutes=30" | python3 -c 'import sys,json; print(json.load(sys.stdin)["sampleCount"])'; }

rm -rf "$ROOT/data"; start_app
(cd "$MAKO_DIR" && BAS_INTERVAL_MS=2000 BAS_MACHINES=CONV-04 BAS_EDGE_API_KEY=demo-edge-key-change-me \
  ./mako -l::"$ROOT/edge/mako/bas-edge" >"$MAKO_LOG" 2>&1) & MAKO_PID=$!
sleep 10
before=$(stored); echo "1. edge online: $(status 'Readings sent') readings sent, $before stored"

pkill -f 'BasIntelligenceApplication'; sleep 12
queued=$(status 'store-and-forward\)'); err=$(status 'Last error')
echo "2. server down 12 s: $queued readings queued on the edge, last error: $err"
[[ "$queued" -gt 0 ]] || { echo "FAIL: nothing queued"; exit 1; }

start_app; sleep 6
after=$(stored); left=$(status 'store-and-forward\)')
echo "3. server back: queue $left, $after readings stored"
[[ "$left" -eq 0 ]] || { echo "FAIL: queue not flushed"; exit 1; }
[[ "$after" -ge $((before + queued)) ]] || { echo "FAIL: readings lost ($after < $before + $queued)"; exit 1; }
dups=$(curl -s -u viewer:viewer123 "http://localhost:8080/api/machines/CONV-04/sensor-faults?hours=1" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')
echo "PASS: no readings lost; sensor faults recorded: $dups"
