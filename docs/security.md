# Security

## Roles

| Account type | Grants | Can do | Sees |
|---|---|---|---|
| Admin | ADMIN, ENGINEER, VIEWER | manage users, factories, edge gateways, technician assignments; audit log, agent activity, Actuator; delete manuals; everything an engineer does | all factories |
| Engineer | ENGINEER, VIEWER | register machines, inspect images, read predictions, run investigations, create and review maintenance drafts (never their own), assign work, issue reports | own factory |
| Technician | TECHNICIAN, VIEWER | view assigned machines and their maintenance records, complete assigned work orders, confirm failures | machines assigned in **Administration → Technician assignments**, plus machines of work orders assigned to them |
| Viewer | VIEWER | read-only dashboards | own factory |
| Kiro agent (`/mcp`, agent key) | AGENT | the nine MCP tools; proposes drafts, never approves | all factories |
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
| investigation | 10 | `POST /api/machines/{code}/investigations`, `POST /machines/{code}/investigations`, `POST /assistant/ask` |
| inspection | 20 | `POST /api/machines/{code}/inspections`, `POST /machines/{code}/inspections[/sample]` |
| prediction | 60 | `GET /api/machines/{code}/risk`, `GET /api/machines/{code}/analysis`, `POST /api/ml/*` |
| search | 60 | `GET /api/manuals/search` |

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
- `EDGE_AUTH_FAILED`, `RATE_LIMITED`
- `USER_CREATED`, `USER_UPDATED`, `USER_ENABLED`, `USER_DISABLED`, `USER_PASSWORD_RESET`
- `FACTORY_CREATED`
- `GATEWAY_CREATED`, `GATEWAY_KEY_ROTATED`, `GATEWAY_REVOKED`
- `MACHINE_ASSIGNED`, `MACHINE_UNASSIGNED`

Events that outsiders can trigger at will are throttled to one entry per key per minute, with a count of suppressed
repeats. Passwords and full keys are never written to the log. MCP tool calls are audited separately in `agent_tool_audit`.

## Known limits

- The agent key is plant-wide (all factories).
- Rate limits and the Basic-auth cache are in memory, so they are per instance.
- Each user belongs to one factory.
- There is no login lockout beyond BCrypt cost and audit; failed logins are counted in `bas.security.login.failed`.
