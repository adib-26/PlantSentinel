# How PlantSentinel uses Kiro

PlantSentinel uses Kiro in two ways. Kiro helped build it, and Kiro is also the reasoning agent inside the finished
product. The application contains no LLM client and no model key. Kiro calls the application's tools over MCP.

Everything below is in the repository under `.kiro/`, `mcp/`, `docs/kiro-mcp.json` and `src/main/java/.../agent/`.

## Kiro CLI

Kiro CLI is the client that runs the agents in this project (tested with Kiro CLI 2.36).

- `mcp/kiro-demo.sh` runs `kiro-cli chat --agent bas-investigator --no-interactive --require-mcp-startup` with the
  question "Why is machine CNC-01 showing a high failure risk?". Kiro calls the MCP tools, writes the report, and tries
  to create a maintenance draft. In a headless run the draft is refused because the tool is set to `ask`. With
  `--create-draft` the script resumes the same session and approves that one write.
- The in-browser **Kiro assistant** page (`/assistant`) starts `kiro-cli` once per message on the machine running the
  app, with the `bas-web-assistant` agent. The server stores the conversation and shows the tool calls and credits used.
  This works only where `kiro-cli login` has been completed. The public Render site leaves it switched off, so nobody
  is asked to bring a Kiro account or key.

## MCP: the app is the server, Kiro is the client

The Spring Boot app is an MCP server (Spring AI 1.1, streamable HTTP at `/mcp`, `X-API-Key` header). It publishes nine
tools and one prompt (`investigate_machine`):

| Group | Tools | Effect |
|---|---|---|
| Read | `machine_get_status`, `machine_get_telemetry`, `machine_detect_anomaly`, `machine_get_failure_prediction`, `machine_get_history`, `machine_get_specifications`, `maintenance_search_manual` | none |
| Write | `maintenance_generate_report` | records an investigation |
| Write | `maintenance_create_draft` | records a PROPOSED recommendation |

Design decisions that came from using Kiro as the client:

- **No approve tool exists.** Kiro can only propose. An engineer other than the proposer approves or rejects in the UI
  (four-eyes rule). This is the product's human-in-the-loop rule enforced by the tool list, not by a prompt.
- **Tool names use underscores** (`machine_get_status`, not `machine.get_status`). Kiro silently drops tools whose
  names do not match `^[a-zA-Z][a-zA-Z0-9_]*$`, so dotted names never appeared in its tool list.
- **Tool selection is `@bas-intelligence/*`.** In the CLI version we used, the bare `@bas-intelligence` form selected
  no tools.
- **Every call is audited** with the acting user `kiro-agent` and shown on the admin Agent activity page. Tool
  failures return structured errors, and `KiroToolCallRobustnessIntegrationTest` covers bad arguments.
- **The server is not Kiro-specific.** `mcp/discover-tools.sh` (the Java MCP SDK) and `mcp/inspector.sh` (the official
  MCP Inspector) use the same endpoint and appear as separate sessions in the audit view.

`docs/kiro-mcp.json` is the MCP config to copy to `.kiro/settings/mcp.json` for plain Kiro sessions. It auto-approves
only the seven read tools.

## Custom agents (`.kiro/agents/`)

| Agent | Used by | Tools | Permissions |
|---|---|---|---|
| `bas-investigator` | `mcp/kiro-demo.sh`, a developer in Kiro CLI | `@bas-intelligence/*` | read tools and `maintenance_generate_report` allowed, `maintenance_create_draft` is `ask` |
| `bas-web-assistant` | the web app, once per chat message | the seven read tools plus `maintenance_generate_report` | `maintenance_create_draft` denied, every built-in tool (shell, files, web) denied |

Both agents set `includeMcpJson: false`, so only the PlantSentinel server is visible to them. The investigator's prompt
is the steering file `machine-investigation-playbook.md`. The web assistant has its own prompt in
`agents/prompts/bas-web-assistant.md` and uses the Auto model.

The web assistant gets a short-lived run token in `BAS_RUN_TOKEN` instead of the shared agent key. The server limits
that token to the signed-in engineer's machines (`KiroAssistantRunScopeScenarioIntegrationTest`). Kiro therefore can't
read another engineer's plant even if it is asked to.

## Spec (`.kiro/specs/bas-intelligence/`)

The project was planned with Kiro's spec workflow:

- `requirements.md` (143 lines): user stories with numbered acceptance criteria in WHEN/IF ... THEN the system SHALL
  form, for example "IF the code is already registered THEN the system SHALL reject the request with HTTP 409".
- `design.md` (98 lines): architecture, data flow and decisions (single Spring Boot app, free Neon plan, in-process ML
  on CPU).
- `tasks.md` (123 lines): the implementation plan. Each task lists the requirement numbers it satisfies. 87 checklist
  items are done. The 5 open ones are under "Next steps": real maintenance history instead of simulated faults, a
  fine-tuned defect CNN, pgvector retrieval, and validating the edge app on real Modbus hardware.

Tests follow the requirements. Each failure mode has a scenario test in `src/test/java/com/basintelligence/scenarios`
(edge outage, authorization, upload validation, prediction retention, assistant run scope and others). Sensor faults,
anomalies, predictions and confirmed failures are kept as separate events (`EventDistinctionIntegrationTest`).

## Steering (`.kiro/steering/`)

| File | Inclusion | Content |
|---|---|---|
| `product.md` | always | users, the 10-step core journey, product principles (cite evidence, human in the loop, state model limits) |
| `tech.md` | always | stack limits (Java 21, Smile 4.4 not 5, free Neon only), commands, code conventions |
| `structure.md` | always | package layout and where each feature lives |
| `security.md` | when Kiro works on `config/`, `web/`, `agent/` or `application*.yml` | four filter chains, key and token rules |
| `haunted-costume.md` | when Kiro works on templates or static files | UI theme rules, CSP-safe scripting |
| `machine-investigation-playbook.md` | manual, and the investigator agent's prompt | how to investigate a machine with the MCP tools and cite evidence |

Always-on steering carried the constraints that would otherwise have to be repeated in every prompt: no new
frameworks, never edit an applied Flyway migration, `JdbcClient` SQL must run on both PostgreSQL and H2, and the
`-Xmx768m` memory budget. File-match steering kept the context small, because security and theme rules load only
when matching files are touched.

## Hooks (`.kiro/hooks/`)

All three run on `PostFileSave` and block with a message when they fail. Each guards a mistake that nothing else in
this project would catch quickly.

| Hook | Matches | Why it exists |
|---|---|---|
| `js-syntax-check` | `static/js/*.js` | The UI has no frontend build, so a syntax error would show up only as a page whose scripts silently stopped. Runs `node --check` on every app script. |
| `csp-template-check` | `templates/*.html` | The CSP is `script-src 'self'`. Inline `<script>`, `on*=` attributes and `javascript:` links are blocked by the browser without any server error. |
| `migration-guard` | `db/**/*.sql` | Flyway validates checksums, so editing an applied migration stops the app on a database (Neon) that already ran it. The hook compares against `HEAD` and says to add `V<n>__...sql` instead. |

## What we did not use

We did not use Kiro powers or property-based testing. Tests are JUnit 5, Mockito, AssertJ, MockMvc and Testcontainers
example-based and scenario tests.

## Limits worth knowing

- The risk model is trained on simulated fault data and the vision model is a fixed-weight baseline. Kiro's
  explanations say so, as the product steering requires.
- The Kiro assistant spends the credits of whoever's Kiro login runs the app.
