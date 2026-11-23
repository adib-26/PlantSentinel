#!/bin/sh
# Render assigns PORT. The assistant agent file must call this process, not a fixed 8080.
set -eu
port="${PORT:-8080}"
agent="/app/.kiro/agents/bas-web-assistant.json"
if [ -f "$agent" ]; then
  sed -i "s#http://localhost:8080/mcp#http://127.0.0.1:${port}/mcp#" "$agent"
fi
exec java -XX:MaxRAMPercentage=40.0 -jar /app/app.jar
