---
inclusion: fileMatch
fileMatchPattern: ['src/main/java/com/basintelligence/config/**', 'src/main/java/com/basintelligence/web/**', 'src/main/java/com/basintelligence/agent/**', 'src/main/resources/application*.yml']
---

# Security rules for PlantSentinel

- Four filter chains in `SecurityConfig`, most specific first: `/api/edge/**` (per-gateway key or the shared edge key), `/mcp` (agent key), `/api/**` (HTTP Basic, stateless; `/api/admin/**` ADMIN), web UI (form login + CSRF; `/admin/**` ADMIN). Keep new endpoints inside the right chain. Details: `docs/security.md`.
- Machine credentials are separate per client class (edge vs agent). Gateway keys are stored only as SHA-256 hashes and bind a gateway to one factory; shared keys are compared in constant time. A missing key means fail closed. `SecretsCheck` refuses identical, short or placeholder keys outside demo/test.
- Factory-level access: every machine read goes through `MachineService` (scoped by `AccessScope`); lists of machine-owned records filter by `MachineService.visible()`. Out-of-scope records answer 404. Never query machines for a person through `MachineRepository` directly.
- Expensive AI endpoints carry `@RateLimited(Kind.…)` (per-user token bucket, 429 + Retry-After). Add it to any new investigation, inspection, prediction or search endpoint.
- Security events (logins, failures, denials, CSRF, bad edge keys, rate limiting, user/factory/gateway/assignment changes) are audited; use `AuditService.recordThrottled` for anything an outsider can trigger. Never log passwords or full keys.
- Never commit real secrets. Demo defaults exist only in `application-demo.yml`. The `neon` profile takes everything from environment variables. `.env` is git-ignored; `.env.example` holds placeholders only.
- Writes require `@PreAuthorize("hasRole('ENGINEER')")`; destructive admin actions require ADMIN.
- Agent (Kiro over MCP) rules, enforced in Java by `AgentToolPolicy` and `AuditingToolCallback`:
  - Allowlist: exactly the nine tools in `AgentToolPolicy.TOOLS`, published over MCP under the names in `McpToolExposure.NAMES`. Startup fails if a published tool is missing from the list or its name suggests approve, execute, shell, file, credential, setting or control.
  - Per-tool authorization: READ tools only read. RECORD tools (`generateMaintenanceReport`, `createMaintenanceDraft`) write an investigation or a PROPOSED recommendation and can be disabled with `bas.agent.record-tools-enabled=false`.
  - Strict input validation (machine-code pattern, length limits, clamped windows), 8000-character argument limit, 60 calls per minute per session, 10 s timeout for reads and 30 s for records, and a 45 s MCP request timeout.
  - Every call is written to `agent_tool_audit` (SUCCESS, ERROR or DENIED) and linked to its MCP session. Drafts link to the exact tool call that created them.
  - Agent drafts must cite existing evidence labels; at most 3 open agent drafts per investigation. Approval needs an engineer other than the proposer, enforced in code and by a database CHECK constraint.
  - The agent key only works on `/mcp`. No MCP tool runs commands, reads files, returns credentials, changes machine settings or controls equipment; the edge API only ingests readings.
- The one endpoint that starts a process is the Kiro assistant (`POST /assistant/messages`, `agent/chat/KiroAssistantService`). It may only run the fixed `kiro-cli chat --agent bas-web-assistant --model auto` command built by `KiroCommand`: an argument list without a shell, the message after `--`, never `--trust-all-tools`, only the Auto model (runs reporting another model are stopped), a minimal environment without the app's secrets, a per-run token instead of the agent key, and the engineer's own access scope. Engineers only, rate limited, one run per engineer, time-limited, audited. Do not add other process launches. Details: `docs/security.md` (Kiro assistant in the browser).
- Validate at trust boundaries: Bean Validation on DTOs with physical plausibility bounds for telemetry; image decode and dimension checks; file signatures (magic bytes) for images and manuals, header-only dimension check before decoding, extension + signature + Tika type for manuals; Tika output size cap.
- File paths for stored images come from the database and are resolved inside the storage directory (`startsWith` check), never from request input.
- Use parameterised SQL via `JdbcClient` named parameters only. Never concatenate input into SQL (constant fragments such as `VisibleMachines.condition` are fine).
- Error responses are RFC 9457 problem details without stack traces.
