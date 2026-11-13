# Design: BAS Intelligence

## Overview

A single Spring Boot 3.5 application (Java 21) runs on the engineer's laptop. Optional managed services: Neon PostgreSQL (free tier) for persistence, and the model access that Kiro already has. All ML, deep learning inference, computer vision and retrieval run in-process on the CPU.

```
 BAS / Mako Server edge ──X-API-Key──▶ /api/edge/telemetry ─┐
 (or built-in simulator)                                    ▼
                                    TelemetryService ──▶ StreamingAnomalyDetector (envelope + z-score)
                                          │                         │
                                          ▼                         ▼
                                    telemetry_reading         anomaly_event      machine.health_status
                                          │
 Engineer (browser) ──form login──▶ Thymeleaf UI ─┐
 Engineer (curl)   ──HTTP Basic──▶ /api/**  ──────┤
 Kiro (MCP client) ──X-API-Key──▶ /mcp (Spring AI MCP, @Tool) ─┤
                                                  ▼
                                      InvestigationService
          ┌──────────────┬──────────────┬─────────┴────────┬──────────────────┐
   TelemetryAnalyzer  RiskService   InspectionService   ManualService     DiagnosisEngine
   (trends + Smile    (Smile LR +   (OpenCV + ONNX      (Tika + chunker   (fault-signature
    IsolationForest)   explanations) Runtime)            + BM25)           rules)
                                                  ▼
                       investigation / evidence / recommendation (PROPOSED)
                                                  ▼
                       human review (four-eyes) ──▶ IncidentReportService ──▶ incident_report
```

## Key decisions

| Decision | Chosen | Alternatives considered | Reason |
|---|---|---|---|
| Agent / LLM | Kiro as MCP client of a Spring AI MCP server | Embedding a chat model via Spring AI ChatClient | No API cost; Kiro does the reasoning; the app stays deterministic and testable. The MCP boundary also enforces "agent proposes, human approves". |
| MCP transport | Streamable HTTP (`/mcp`) | STDIO, SSE | Kiro supports remote HTTP servers with headers; one running process serves UI, API and MCP. |
| Retrieval | BM25 in memory | Embeddings + pgvector | Manuals are term-dense; BM25 needs no model download or extra RAM; rankings are explainable. The `ManualService.search` contract allows a hybrid retriever later. |
| Risk model | Logistic regression on engineered "excess" features | Random forest, gradient boosting | Accurate enough on this signal (AUC ≈ 0.9), trains in milliseconds, and gives exact per-feature contributions. |
| Multivariate anomaly | Isolation Forest fitted per request on the window's earlier 60% | Global model | Adapts to each machine's own normal behaviour; no training pipeline needed. |
| Vision | Fixed-weight ONNX CNN (blur → Laplacian → pooling → logistic) + OpenCV contours | Pretrained classifier | Runs anywhere, no dataset or GPU, documents the ONNX contract. A trained model can be swapped in without code changes. |
| Persistence | JdbcClient + Flyway, portable SQL | JPA | Explicit SQL, low memory, works on both H2 (offline demo) and Neon PostgreSQL. |
| Images | Local disk | Database BYTEA | Keeps Neon free-tier storage (0.5 GB) for structured data. |

## Agent design

One agent (Kiro) with nine allowlisted tools. Kiro runs the tool-calling loop: it requests a tool, Java validates and
executes it, and the result goes back to Kiro for the next step. In Spring AI terms the app is the tool executor;
the model never runs code itself.

```
Kiro ─tools/call─▶ /mcp (agent key) ─▶ AuditingToolCallback
                                         ├─ AgentToolPolicy: allowlist, READ/RECORD access, size + rate limits
                                         ├─ agent_tool_audit row (RUNNING → SUCCESS / ERROR / DENIED)
                                         └─ executor with timeout ─▶ BasMcpTools ─▶ application services
```

| Tool | Access | Evidence class of the result |
|---|---|---|
| getMachineStatus | READ | state and data freshness |
| getSensorReadings | READ | MEASURED |
| detectAnomaly | READ | RULE_BASED events and fault signatures, MEASURED trends, MODEL_PREDICTION Isolation Forest and vision |
| predictFailureRisk | READ | MODEL_PREDICTION with limitations |
| getHistoricalFailures | READ | DOCUMENTED |
| searchMaintenanceManual | READ | DOCUMENTED, with "Document › Section, p. N" citations |
| getMachineSpecifications | READ | DOCUMENTED operating envelope |
| generateMaintenanceReport | RECORD | report in sections by evidence class; agent text stored as AI_INTERPRETATION |
| createMaintenanceDraft | RECORD | PROPOSED recommendation; approval only by another engineer |

## Data model

`machine`, `telemetry_reading` (indexed by machine and time), `anomaly_event`, `inspection`, `manual_document`, `manual_chunk`, `investigation`, `investigation_evidence` (ref_label E1..En), `recommendation` (PROPOSED/APPROVED/REJECTED, proposer, reviewer), `incident_report` (one per investigation), `audit_log`. See `V1__core_schema.sql`.

## Investigation algorithm

1. Analyse the last 2 h: per-metric trend and Isolation Forest score.
2. Load anomalies (2 h), risk (mean of the last 12 readings), inspections (7 days).
3. DiagnosisEngine evaluates fault signatures, for example:
   - Bearing wear: vibration ≥ 1.5× nominal, or +30% with a positive slope. Confidence rises with envelope severity, a co-rising temperature, the multivariate flag, and a defect inspection.
   - Cooling degradation: temperature ≥ 1.15× nominal or +10% with vibration normal.
   - Overload: torque ≥ 1.25× nominal or +15%; stronger if speed sags.
   - Pressure loss: pressure ≤ 0.85× nominal or −12%.
   - Surface defect: DEFECT inspection; confidence 0.35 + 0.4 × probability.
4. Retrieve manual passages per actionable hypothesis (primary: 3; secondary ≥ 60%: 2), deduplicated.
5. Build labelled evidence, a cited explanation and PROPOSED recommendations with priority from envelope severity and risk level.

## Error handling

Services throw `NotFoundException` (404) and `InvalidStateException` (409). REST maps these to RFC 9457 problem details; the UI renders an error page. Validation errors return 400. Agent tool errors surface as MCP tool errors.

## Security

See `.kiro/steering/security.md`. Four filter chains, separate keys for edge and agent, BCrypt-hashed configured users, CSRF and a strict CSP for the UI, audit log for all state changes.

## Testing strategy

- Unit: detectors, analyzer, risk model (AUC, monotonicity, explanation consistency), BM25, chunker, diagnosis rules, review rules (Mockito).
- Vision regression: the real ONNX + OpenCV pipeline against 30 synthetic parts per class.
- Integration (MockMvc + H2): the full journey from registration to closed report; security of every chain.
- PostgreSQL: Flyway migrations on a Testcontainers PostgreSQL 17 (`./mvnw verify`, skipped without Docker).
