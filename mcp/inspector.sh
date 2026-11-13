#!/usr/bin/env bash
# Uses the official MCP Inspector (a third-party client, Node.js) to list and call BAS Intelligence tools.
#   ./mcp/inspector.sh tools/list
#   ./mcp/inspector.sh tools/call machine_get_status machineCode=CNC-01
#   ./mcp/inspector.sh prompts/list
set -euo pipefail
URL="${BAS_MCP_URL:-http://localhost:8080/mcp}"
KEY="${BAS_AGENT_API_KEY:-demo-agent-key-change-me}"
METHOD="${1:-tools/list}"
ARGS=(--cli "$URL" --transport http --header "X-API-Key: $KEY" --method "$METHOD")
if [[ "$METHOD" == "tools/call" ]]; then
  ARGS+=(--tool-name "$2"); shift 2
  [[ $# -gt 0 ]] && ARGS+=(--tool-arg "$@")
fi
exec npx --yes @modelcontextprotocol/inspector "${ARGS[@]}"
