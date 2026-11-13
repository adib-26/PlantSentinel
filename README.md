# PlantSentinel

AI-powered industrial monitoring, predictive maintenance and diagnostic assistant. It runs on an 8 GB laptop CPU
with **RM0 recurring cost**: the Spring Boot app runs locally, data lives in Neon's free PostgreSQL plan, and **Kiro**
is the reasoning agent, connected over MCP.

A factory engineer can register machines, watch telemetry, see anomalies and failure risk, inspect component images,
ask for an investigation backed by maintenance-manual evidence, approve or reject the recommendations (four-eyes
rule), and issue an incident report.

## Why

A machine rarely fails without a trail: vibration drifts, a temperature climbs, a manual already describes that pattern. Those signals usually live in different tools, and a hosted model that "explains" the failure often cannot show the chart, the prediction, or the manual page it used. It can also be asked to act on its own.

PlantSentinel keeps that trail in one place on a laptop. Detectors and risk models score the machine from its own telemetry. An investigation must cite telemetry, a model output, or a manual section. The agent can only propose a recommendation. A second engineer approves or rejects it before anyone writes a work order. The app holds no LLM API key, so the reasoning stays outside the process that stores the plant data.

## Architecture

```
Simulator / sensors ─▶ BAS / Mako Server edge (Lua) ──HTTPS + X-API-Key──▶ Spring Boot (localhost:8080)
                                                                            │  REST · validation · security
Kiro agent ──MCP (streamable HTTP, /mcp, X-API-Key)────────────────────────▶│  orchestration · audit
Browser ──form login──▶ Thymeleaf dashboard ───────────────────────────────▶│
                                                                            ├─ ML: detectors, Isolation Forest, Smile models
                                                                            ├─ Vision: OpenCV + ONNX Runtime (CPU)
                                                                            ├─ RAG: Tika + PostgreSQL full-text search
                                                                            └─ JDBC over TLS ─▶ Neon PostgreSQL (free plan)
```

Kiro replaces a hosted LLM API: instead of the app calling a model, Kiro calls the app's tools. The app stays
deterministic and testable, holds no LLM keys, and Kiro can only *propose* actions because no approve tool exists.

## Quick start

```bash
brew install openjdk@21                          # Java 21; Maven comes from ./mvnw
./mvnw spring-boot:run                           # offline demo: embedded H2, simulated plant
open http://localhost:8080                       # engineer / engineer123, reviewer / reviewer123
```

Run against Neon (free plan):

```bash
npm i -g neon && neon login
neon init -y --no-agent-setup --no-config --org-id <org> --project-id <project>   # writes .env.local
# add BAS_* secrets to .env.local (see .env.example)
SPRING_PROFILES_ACTIVE=neon ./mvnw spring-boot:run
```

Connect Kiro: copy `docs/kiro-mcp.json` to `.kiro/settings/mcp.json`, export `BAS_AGENT_API_KEY`, then run `/mcp`.
Ask: *"Why is machine CNC-01 showing a high failure risk?"* (or load the `investigate_machine` prompt).

## Screens

Thymeleaf + Bootstrap, no frontend build. Every AI-derived value is labelled **measured**, **rule-based**,
**model prediction**, **documented** or **AI interpretation**.

| Screen | Path | What it shows |
|---|---|---|
| 1 Factory overview | `/` | machine counts (total, normal, needing attention), active alerts, awaiting approval, factory risk summary, recent maintenance and investigations |
| 2 Machine monitoring | `/machines/{code}` | details, 5 telemetry charts (2 h raw, 24 h / 7 d from 5-minute rollups), connectivity, data quality (interval, gaps, completeness, stuck sensors, edge vs simulated), historical readings |
| 3 Predictive analytics | `/analytics` | Isolation Forest scores, risk estimates and trends, risk-model performance and feature weights, per-prediction log-odds contributions, prediction history chart, versions of all models |
| 4 Kiro assistant | `/assistant`, `/investigations/{id}` | questions in plain language, investigation history (pipeline vs Kiro), evidence map, linked `[E2]` citations, tool execution trace, confidence and limitations |
| 5 Maintenance | `/maintenance` | pending approvals, recommendations, work orders with technician assignment and completion, incident reports, approval decisions and audit trail |
| 6 Knowledge | `/manuals`, `/manuals/{id}` | upload, browse, full-text search linked to exact sections, extracted sections with page numbers, which investigations cited each section |

On an investigation page every evidence item has an **open source** link: telemetry opens the machine charts,
predictions open the analytics history, and manual evidence opens the cited section of the document.

**Simulated machine incident.** On a simulated machine page, an engineer opens *Simulate a machine incident*. They
choose a fault (bearing wear, cooling degradation, pressure leak or overload) and how many minutes it takes to reach
full severity (1 to 120). From then on the machine's readings follow that fault through normal ingestion. The anomaly
detectors, health status and risk react to them. When the fault peaks, an investigation labelled *Simulated incident
SIM-n* starts by itself. Its recommendation still needs approval from a second engineer. The fault is held for 30
minutes, or until someone presses *Stop simulation*; then the normal simulation resumes. Machines fed by a real BAS
edge node cannot be simulated. API: `POST /api/machines/{code}/incidents` `{"fault":"BEARING_WEAR","rampMinutes":5}`
and `POST /api/incidents/{id}/stop`.

Demo logins: `engineer` / `engineer123`, `reviewer` / `reviewer123` (second engineer for approvals),
`technician` / `technician123` (sees machines an admin assigns to them and completes work assigned to them),
`viewer` / `viewer123`, `admin` / `admin123` (Administration: users, factories, edge gateways, assignments).
Security details: `docs/security.md`. Monitoring (Actuator, metrics, structured logs): `docs/monitoring.md`.

## MCP integration

The same nine Spring services are reachable two ways:
1. **Ordinary tool calling** (in process): `AgentToolset` holds Spring AI `ToolCallback`s named after the Java methods
   (`getMachineStatus`, ...). Tests drive the full investigation through these callbacks.
2. **MCP** (streamable HTTP at `/mcp`): `McpToolExposure` publishes the same callbacks under namespaced names with
   titles and read-only / idempotent hints:

| MCP tool | Spring service behind it | Access |
|---|---|---|
| `machine_get_status` | MachineService, InvestigationService | read |
| `machine_get_telemetry` | TelemetryService, TelemetryRollupService | read |
| `machine_detect_anomaly` | TelemetryAnalyzer, DiagnosisEngine, InspectionService | read |
| `machine_get_failure_prediction` | RiskService, PredictionService | read |
| `machine_get_history` | InvestigationService, MaintenanceService | read |
| `machine_get_specifications` | DeviceTypeCatalog | read |
| `maintenance_search_manual` | ManualService (PostgreSQL full-text search) | read |
| `maintenance_generate_report` | InvestigationService | records an investigation |
| `maintenance_create_draft` | InvestigationService | records a PROPOSED recommendation |

The names use `namespace_action` rather than `machine.get_status`. The MCP specification allows dots, but Kiro only
accepts `^[a-zA-Z][a-zA-Z0-9_]*$` and silently drops other tools, so underscores work in every client. The server also
publishes the `investigate_machine` prompt.

Show that any MCP client can use it (app running):

```bash
./mcp/discover-tools.sh                                   # stand-alone Java MCP client (MCP SDK, no Spring)
./mcp/inspector.sh tools/list                             # official MCP Inspector CLI (Node.js)
./mcp/inspector.sh tools/call machine_get_status machineCode=CNC-01
```

Both appear as separate sessions on the admin **Agent activity** page, and every call is audited.

Kiro as the client (tested with Kiro CLI 2.36): `.kiro/agents/bas-investigator.json` declares the server and selects
`@bas-intelligence/*` (the bare `@bas-intelligence` form selected nothing in this version). Read tools and the report
are allowed, and `maintenance_create_draft` is `ask`, so Kiro needs a human yes before it writes a draft.

```bash
./mcp/kiro-demo.sh                 # Kiro investigates CNC-01; the draft is refused without approval
./mcp/kiro-demo.sh --create-draft  # resume and explicitly approve that one write
```

## The agent

One agent (Kiro) and nine Java tools: `getMachineStatus`, `getSensorReadings`, `detectAnomaly`, `predictFailureRisk`,
`getHistoricalFailures`, `searchMaintenanceManual`, `getMachineSpecifications`, `generateMaintenanceReport` and
`createMaintenanceDraft`. Kiro decides which tool to call; Java validates, executes and audits every call. Every
result is labelled **measured**, **rule-based**, **model prediction**, **documented** or **AI interpretation**, and the
report keeps those sections apart.

The agent is an investigator, not an administrator. It cannot run commands, read files, see credentials, change
machine settings, control equipment or approve its own drafts. The tool allowlist, input limits, rate limit,
timeouts and audit trail are enforced in `agent/AgentToolPolicy` and `agent/AuditingToolCallback`. Only an engineer
other than the proposer can approve, which a database constraint also enforces.

Train the ML models: `ml/run-ml-lab.sh` (see [ml/README.md](ml/README.md)).

## What is where

| Area | Location |
|---|---|
| Schema (Flyway, PostgreSQL + H2) | `src/main/resources/db/migration`, `db/vendor/postgresql` |
| Streaming anomaly detection, trends | `analytics/` |
| Failure risk, stored predictions | `risk/` |
| Trained models, prediction API | `ml/`, `web/api/MlApiController` |
| ML lab (EDA, training, evaluation, export) | `mllab/`, `ml/` |
| Vision | `vision/`, `ml/build_defect_model.py` |
| Manuals and RAG | `knowledge/` |
| Investigations, approvals, reports | `investigation/`, `maintenance/` |
| Kiro MCP tools and audit | `agent/` |
| Edge (Mako Server) | `edge/` |
| Kiro steering and specs | `.kiro/` |

## Tests

`./mvnw test` runs 141 unit and integration tests (see [docs/testing.md](docs/testing.md) for the test matrix and failure scenarios), including the full engineer journey, the example agent investigation through the
real tool callbacks, agent policy, security chains, ML metrics and the trained-model API. `./mvnw verify` adds PostgreSQL Testcontainers tests (requires Docker).

## Licence

This version of PlantSentinel is released under the GNU General Public License, version 2 only. The licence is the file `LICENSE`, copied verbatim from the Free Software Foundation. Its copyright notice is unchanged: Copyright (C) 1989, 1991 Free Software Foundation, Inc. No other licence is granted.

This source tree had no copyright or licence headers to remove. Dataset attribution in the model cards and in `ml/datasets/README.md` is unchanged. BAS and Mako Server are not part of this source; their copyright and licence notices stay in the [BAS repository](https://github.com/RealTimeLogic/BAS).

## BAS licensing

The [BAS repository](https://github.com/RealTimeLogic/BAS) offers GPLv2 and commercial licensing options. If you distribute a derivative BAS application, understand the GPLv2 obligations, including the treatment of application content hosted by a derivative product. For a public portfolio, keep your integration separate and review the licence before distributing a combined package.

PlantSentinel does not vendor BAS or Mako Server. `edge/build-mako-macos.sh` downloads the official sources into a local cache and builds them there. The Lua edge app in `edge/mako/bas-edge/` stays separate from those sources, and the Spring Boot application does not link against BAS.

## Free-plan safeguards

Small Hikari pool with no idle connections, background jobs that pause after 20 minutes without use (so Neon scales
to zero), 3-day raw telemetry retention with 5-minute rollups kept 90 days, and images stored on local disk. Neon's
free plan suspends instead of billing when a limit is reached.
