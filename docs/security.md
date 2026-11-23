# Security

## Roles

| Account type | Grants | Can do | Sees |
|---|---|---|---|
| Admin | ADMIN, ENGINEER, VIEWER | manage users, factories, edge gateways, technician assignments; audit log, agent activity, Actuator; delete manuals; everything an engineer does | all factories |
| Engineer | ENGINEER, VIEWER | register machines, inspect images, read predictions, run investigations, create and review maintenance drafts (never their own), assign work, issue reports | own factory |
| Technician | TECHNICIAN, VIEWER | view assigned machines and their maintenance records, complete assigned work orders, confirm failures | machines assigned in **Administration → Technician assignments**, plus machines of work orders assigned to them |
| Viewer | VIEWER | read-only dashboards | own factory |
| Kiro agent (`/mcp`, agent key) | AGENT | the nine MCP tools; proposes drafts, never approves | all factories |
| Kiro assistant run (`/mcp`, run token) | AGENT, for one engineer | the read tools and the report tool; no drafts | the engineer's own machines |
| Edge gateway (`/api/edge`, gateway key) | EDGE | post telemetry only | machines of its own factory |

Writes are checked with `@PreAuthorize` on the controller method. `/admin/**` and `/api/admin/**` also require ADMIN
in the URL rules. Which machines a person sees is decided by `users/AccessScope` and enforced in `MachineService`
(lists, `get`, `getByCode`) and in the services that load investigations, recommendations, work orders and inspection
images. A machine outside the caller's scope answers **404**, so its existence is not revealed. Background jobs run
without a request and see everything.

## Authentication and passwords

- People: form login (web UI, session, CSRF) or HTTP Basic (`/api/**`, stateless). Accounts are in the `users` table.
- Passwords are hashed with BCrypt behind a `DelegatingPasswordEncoder` (`{bcrypt}$2a$10$…`). Older hashes without the
  prefix still match and are rewritten at the next login. New and reset passwords need 12 to 72 bytes, must not contain
  the username and must not be repetitive.
- After an administrator resets a password, changes a role or factory, or disables a user, that user's web sessions end
  and cached HTTP Basic checks are dropped (`UserAccessChanged`). Administrators cannot disable or demote themselves, and
  the last enabled administrator cannot be removed.

## Device authentication

Each BAS / Mako Server edge node gets its own key from **Administration → Edge gateways** (or `POST /api/admin/gateways`):
`bas_gw_<8 hex>_<43 base64url>`, 256 random bits. The key is shown once. Only its SHA-256 hash and the public prefix are
stored. A gateway is bound to one factory. Readings for machines in other factories are reported as `UNKNOWN_MACHINE`.
The stored `gateway_id` is the gateway code from the key, not the `gatewayId` the request body declares. Rotating
issues a new key and the old key stops working at once. Revoking disables the gateway.

The shared `BAS_EDGE_API_KEY` still works for existing edge nodes. It has no gateway identity and no factory restriction.
Leave it unset once all nodes have their own keys.

## Kiro assistant in the browser

The Kiro assistant page lets an engineer talk to Kiro. Every account with the Engineer role can use it, which in the demo are `engineer`, `reviewer` and `admin` (an admin also holds the Engineer role); `technician` and `viewer` cannot. The web app is the MCP server, and Kiro needs a model, so each
message runs Kiro CLI on the server as a child process. This is the only place the app starts a process, and it
always runs the same fixed command:

```
<kiro-cli> chat --v3 --agent bas-web-assistant --model auto --no-interactive --output-format stream-json --require-mcp-startup [--resume-id <session>] -- <message>
```

| Safeguard | How |
|---|---|
| No shell, no argument injection | `ProcessBuilder` with an argument list (`agent/chat/KiroCommand`). The message is one argument after `--`. The agent name and resumed session id must match strict patterns. `--trust-all-tools` and `--trust-tools` are never passed. |
| Narrow agent | `.kiro/agents/bas-web-assistant.json` lists only the seven read tools and `maintenance_generate_report`, denies `maintenance_create_draft` and every built-in tool (shell, file, web). Its prompt is `.kiro/agents/prompts/bas-web-assistant.md`. |
| Auto model only | Every run passes `--model auto`, also when resuming (a resumed session otherwise keeps the model it was stored with), and the agent file sets `"model": "auto"`. Kiro reports its model in `config_option_update` events. A run whose model is not `auto` when its turn starts, that switches model during the turn, or that never reports one is stopped and its reply discarded (`FAILED`). The model is a constant (`KiroCommand.MODEL`), not a setting. |
| Per-run credential | Each run gets its own token (`bas-run-` + 256 random bits) in `BAS_RUN_TOKEN`. It is valid for that run only, at most the time limit plus one minute, and revoked when the run ends. Only its SHA-256 is kept, in memory. The shared agent key never reaches the process. A run token is never compared with the shared key, and `SecretsCheck` refuses a shared key with the `bas-run-` prefix. |
| Engineer's scope | An MCP request with a run token is authenticated as `kiro-agent` with the run attached. `AccessScopeService` gives it the engineer's scope, re-read from `users` on every call, so a disabled account or lost engineer role gives an empty scope. `AuditingToolCallback` passes the identity to the thread that runs read tools. |
| No drafts, whatever the agent file says | `AgentToolPolicy` refuses `createMaintenanceDraft` for run tokens (`DENIED`). Reports are written as `kiro-agent`, so the agent's citation and four-eyes rules still apply. The engineer can add a recommendation on the report page; another engineer reviews it. |
| Clean environment | The child gets `HOME`, `USER`, `LOGNAME`, `PATH` (the kiro-cli folder plus system folders), `LANG`, `TMPDIR` and XDG/D-Bus variables if set, and the run token. Database URLs, keys and passwords are not passed. |
| Limits | Engineers only (`@PreAuthorize("hasRole('ENGINEER')")`; `reviewer` and `admin` have that role, `technician` and `viewer` do not), 5 messages per minute (`bas.rate-limit.assistant-messages-per-minute`), one run per engineer, at most 2 runs on the server (`bas.assistant.max-concurrent-runs`), 2,000 characters per message, a 5-minute limit after which the process and its children are stopped, 1 MB per output line and 20 MB per run. |
| Private and audited | A conversation is visible only to the engineer who started it; anyone else gets 404. `KIRO_ASSISTANT_ASKED` and `KIRO_ASSISTANT_ANSWERED` go to the audit log. Every tool call of a run is in `agent_tool_audit` with `on_behalf_of` and `assistant_message_id`. Refused agent keys and run tokens are audited as `AGENT_AUTH_FAILED`. |

Things to know:

- Runs use the Kiro login and credits of the operating-system user running the app. Every engineer's message spends
  them. Credits per message are shown on the page and in `bas.assistant.credits`.
- The agent file sends MCP calls to `http://localhost:8080/mcp`, so the assistant needs the app on port 8080.
- Kiro must trust the workspace folder. Open it once in interactive Kiro and choose to trust it.
- Kiro keeps its own copy of each conversation under `~/.kiro/sessions`.
- The run token is in the child's environment. Other processes of the same OS user can read it while the run lasts,
  but that user already owns the Kiro login.
- It is on by default in the demo profile and off on Neon (`BAS_KIRO_ASSISTANT_ENABLED`). It runs on macOS and Linux.

## Uploads

- Multipart limit is 10 MB per file and 12 MB per request (413).
- **Images:** the leading bytes must be PNG, JPEG or BMP. The dimensions are read from the header before decoding, and
  images must be 32 to 8192 px per side, so a decompression bomb is refused without allocating pixels. Stored files get
  server-generated names, and paths come only from the database.
- **Manuals:** the extension must be pdf, docx, doc, txt or md, and the file signature must match it. After parsing, the
  type Tika detected must be a document type. Text extraction is capped at 2,000,000 characters. Original file names
  are reduced to safe display values (`Text.safeFilename`).

## Rate limiting

Each user has an in-memory token bucket per kind of expensive operation (`bas.rate-limit.*`, per minute, bursts up to
the same number). Exceeding it returns **429** with `Retry-After`, and the rejection is audited.

| Kind | Default | Endpoints |
|---|---|---|
| investigation | 10 | `POST /api/machines/{code}/investigations`, `POST /machines/{code}/investigations`, `POST /reports` |
| inspection | 20 | `POST /api/machines/{code}/inspections`, `POST /machines/{code}/inspections[/sample]` |
| prediction | 60 | `GET /api/machines/{code}/risk`, `GET /api/machines/{code}/analysis`, `GET /api/machines/{code}/predictions/{id}/verification`, `GET /analytics/{code}/predictions/{id}`, `POST /api/ml/*` |
| search | 60 | `GET /api/manuals/search` |
| assistant | 5 | `POST /assistant/messages` (each message starts a Kiro CLI run) |

MCP calls keep their own limit of 60 per minute per session. Limits apply per application instance.

## Secrets

All secrets come from environment variables or the git-ignored `.env.local` (see `.env.example`). Demo defaults exist
only in `application-demo.yml`. At startup, `SecretsCheck` refuses identical edge and agent keys in every profile.
Outside `demo` and `test` it also refuses machine keys shorter than 24 characters and placeholder keys or passwords
(`change-me`, demo defaults). Generate keys with `openssl rand -hex 32`. Secret values are never logged.

## Audit log

`audit_log` (Administration → Audit log) records workflow actions and these security events:

- `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `LOGOUT`
- `ACCESS_DENIED`, `CSRF_REJECTED`
- `EDGE_AUTH_FAILED`, `AGENT_AUTH_FAILED`, `RATE_LIMITED`
- `KIRO_ASSISTANT_ASKED`, `KIRO_ASSISTANT_ANSWERED`
- `USER_CREATED`, `USER_UPDATED`, `USER_ENABLED`, `USER_DISABLED`, `USER_PASSWORD_RESET`
- `FACTORY_CREATED`
- `GATEWAY_CREATED`, `GATEWAY_KEY_ROTATED`, `GATEWAY_REVOKED`
- `MACHINE_ASSIGNED`, `MACHINE_UNASSIGNED`

Events that outsiders can trigger at will are throttled to one entry per key per minute, with a count of suppressed
repeats. Passwords and full keys are never written to the log. MCP tool calls are audited separately in `agent_tool_audit`.

## Known limits

- The shared agent key is plant-wide (all factories). Kiro assistant runs are limited to their engineer's factory.
- Rate limits and the Basic-auth cache are in memory, so they are per instance.
- Each user belongs to one factory.
- There is no login lockout beyond BCrypt cost and audit; failed logins are counted in `bas.security.login.failed`.
