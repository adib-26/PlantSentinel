#!/usr/bin/env bash
# Kiro as the MCP client: runs the example investigation headlessly with the bas-investigator agent
# (.kiro/agents/bas-investigator.json). Read tools and the report are allowed; maintenance_create_draft is
# "ask", so in a headless run Kiro is refused and reports the draft instead of creating it.
# Pass --create-draft to resume the same conversation and explicitly approve that one write.
set -euo pipefail
cd "$(dirname "$0")/.."
export BAS_AGENT_API_KEY="${BAS_AGENT_API_KEY:-demo-agent-key-change-me}"
MACHINE="${MACHINE:-CNC-01}"
kiro-cli chat --v3 --agent bas-investigator --no-interactive --require-mcp-startup \
  "Why is machine $MACHINE showing a high failure risk? Investigate with the BAS Intelligence tools, generate the maintenance report, and then create a maintenance draft for the most important action." \
  2>/dev/null
if [[ "${1:-}" == "--create-draft" ]]; then
  SESSION=$(grep -l "Why is machine $MACHINE showing a high failure risk" ~/.kiro/sessions/*/sess_*/messages.jsonl | xargs ls -t | head -1 | xargs dirname | xargs basename)
  kiro-cli chat --v3 --agent bas-investigator --no-interactive --require-mcp-startup --resume-id "$SESSION" \
    --trust-all-tools "I approve this one maintenance_create_draft call. Create the draft you proposed." 2>/dev/null
fi
