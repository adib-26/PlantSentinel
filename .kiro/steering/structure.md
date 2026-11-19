# Project structure

```
src/main/java/com/basintelligence/
  agent/          the nine MCP tools (BasMcpTools), AgentToolPolicy, audited tool wrapper, investigate_machine prompt;
                  chat/ the Kiro assistant (runs Kiro CLI per message with a run token, stores conversations)
  analytics/      StreamingAnomalyDetector (envelope + z-score), TelemetryAnalyzer (trends + Isolation Forest)
  common/         AuditService, exceptions, Text helpers
  config/         BasProperties, SecurityConfig, API-key filter, StartupInitializer (seeding)
  investigation/  DiagnosisEngine (fault rules), InvestigationService (orchestration), IncidentReportService
  knowledge/      Tika extraction, TextChunker, Bm25Index, ManualService (RAG)
  machine/        Machine, MachineType (operating envelopes), Metric, MachineService
  risk/           RiskFeatures, SyntheticFailureData, FailureRiskModel (Smile LR), RiskService
  telemetry/      ingestion service, edge REST endpoint, simulation/ (generator, simulator, demo plant)
  vision/         ImageProcessor (OpenCV), DefectClassifier (ONNX Runtime), InspectionService, samples
  web/            api/ REST controllers, ui/ Thymeleaf controllers, exception handlers
src/main/resources/
  db/migration/   Flyway SQL        manuals/   bundled maintenance manuals (Markdown, seeded at startup)
  models/         defect-detector.onnx           templates/, static/   UI
edge/             Mako Server edge app (Lua) and a curl-based edge client
ml/               Python script that builds the ONNX baseline model
docs/             architecture, ML model cards, demo script, security notes
.kiro/            steering, specs, hooks and MCP settings for Kiro
```

Bundled manuals are named `<MACHINE_TYPE>-<topic>.md` (lower case) to scope them to a machine type; any other name is a general manual. The first `# ` heading is the title.
