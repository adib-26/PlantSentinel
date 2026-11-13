#!/usr/bin/env bash
# Connects a separate MCP client (plain Java, MCP SDK, no Spring context) to a running BAS Intelligence
# and prints the tools and prompts it discovers, then runs a read-only investigation.
#   ./mcp/discover-tools.sh [http://localhost:8080] [CNC-01]
# Uses BAS_AGENT_API_KEY (demo default: demo-agent-key-change-me).
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q -B compile dependency:build-classpath -Dmdep.outputFile=target/mcp-classpath.txt
java -cp "target/classes:$(cat target/mcp-classpath.txt)" com.basintelligence.mcpclient.McpDiscoveryClient "$@" 2>/dev/null
