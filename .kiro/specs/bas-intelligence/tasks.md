# Implementation plan: BAS Intelligence

- [x] 1. Project foundation
  - [x] 1.1 Maven project on Spring Boot 3.5 / Java 21 with Spring AI BOM, wrapper, demo (H2) and neon profiles
  - [x] 1.2 Flyway schema V1 portable between PostgreSQL and H2 PostgreSQL mode
  - [x] 1.3 Typed `BasProperties`, `Clock` bean, audit log service
  - _Requirements: 11.5, 11.6_

- [x] 2. Machine registry
  - [x] 2.1 `MachineType` operating envelopes and `Metric` channels
  - [x] 2.2 Repository, service, validation and duplicate-code check
  - _Requirements: 1.1-1.5_

- [x] 3. Telemetry ingestion and edge integration
  - [x] 3.1 `/api/edge/telemetry` batch endpoint with plausibility validation and future-timestamp guard
  - [x] 3.2 Fault-scenario generator, live simulator that yields to real edge data, demo plant seeding with 3 h history
  - [x] 3.3 Mako Server edge app (Lua) with store-and-forward queue, plus a curl edge client
  - [x] 3.4 Retention job
  - _Requirements: 2.1-2.7_

- [x] 4. Anomaly detection
  - [x] 4.1 Streaming envelope and z-score detectors with alert cooldown
  - [x] 4.2 Window analyzer with trend statistics and Isolation Forest
  - [x] 4.3 Unit tests
  - _Requirements: 3.1-3.5_

- [x] 5. Failure-risk model
  - [x] 5.1 Feature engineering and synthetic fault-mode training data
  - [x] 5.2 Smile logistic regression with hold-out evaluation and log-odds explanations
  - [x] 5.3 Unit tests (AUC, monotonicity, explanation consistency)
  - _Requirements: 4.1-4.3_

- [x] 6. Visual inspection
  - [x] 6.1 ONNX baseline model builder (Python) and calibration against synthetic parts
  - [x] 6.2 OpenCV preprocessing, localisation, annotation; ONNX Runtime classifier
  - [x] 6.3 Inspection service with disk storage, path-traversal guard and metrics
  - [x] 6.4 Regression test over 30 parts per class
  - _Requirements: 5.1-5.4_

- [x] 7. Manual retrieval (RAG)
  - [x] 7.1 Tika extraction, section-aware chunker, BM25 index
  - [x] 7.2 Upload, dedupe by SHA-256, bundled manuals seeded at startup
  - [x] 7.3 Unit tests
  - _Requirements: 6.1-6.4_

- [x] 8. Investigation workflow
  - [x] 8.1 Diagnosis engine with fault-signature rules and corroboration
  - [x] 8.2 Orchestration: evidence labels, cited explanation, recommendations per actionable hypothesis
  - [x] 8.3 Review rules (four-eyes, conditional update against double review, reopen on new proposal)
  - [x] 8.4 Markdown incident report and download
  - _Requirements: 7.1-7.5, 8.1-8.5, 9.1-9.3_

- [x] 9. Kiro agent integration
  - [x] 9.1 Spring AI MCP server (streamable HTTP) with `@Tool`s and ISO-8601 JSON results (replaced by the nine agent tools in task 15)
  - [x] 9.2 Agent API-key chain and async-dispatch handling for SSE responses
  - [x] 9.3 Kiro MCP configuration, investigation playbook steering
  - _Requirements: 10.1-10.4_

- [x] 10. Web UI, API and operations
  - [x] 10.1 Security chains, BCrypt users, CSRF, CSP, method security
  - [x] 10.2 Dashboard, machine detail with live charts, inspection upload, investigation review, manuals, audit log
  - [x] 10.3 REST API with OpenAPI docs and problem-detail errors
  - [x] 10.4 Micrometer metrics and Actuator
  - _Requirements: 11.1-11.4_

- [x] 11. Verification and documentation
  - [x] 11.1 End-to-end MockMvc journey test and security tests
  - [x] 11.2 Testcontainers PostgreSQL migration test
  - [x] 11.3 README, architecture, model cards, demo script

- [x] 12. Database architecture (Neon)
  - [x] 12.1 Flyway V1-V9: factories, users, device types and limits, devices (UUID), telemetry and 5-minute rollups, anomaly events, model predictions, maintenance records, documents and chunks, investigations, approvals, agent sessions and tool audit
  - [x] 12.2 PostgreSQL full-text search for RAG (vendor migration V100), BM25 fallback on H2
  - [x] 12.3 Neon connection from `.env.local`, small pool, idle pause for background jobs to stay within the free plan
  - [x] 12.4 Users in the database (BCrypt), four-eyes rule enforced by a CHECK constraint, MCP tool calls audited

- [x] 13. ML development sequence
  - [x] 13.1 Dataset fetch script with checksums and licence notes (AI4I 2020, C-MAPSS, CWRU; MVTec manual)
  - [x] 13.2 EDA and data-quality reports, feature definitions shared by training and serving
  - [x] 13.3 Logistic baseline, decision tree, random forest, gradient boosting on AI4I; selection by validation PR-AUC
  - [x] 13.4 Isolation Forest anomaly case study on CWRU (false alarm rate, detection delay) and fault classifier
  - [x] 13.5 RUL regression on C-MAPSS FD001 (MAE, RMSE, R², NASA score)
  - [x] 13.6 Versioned artifacts with model cards and checksums; registry with deserialisation allow-list
  - [x] 13.7 Prediction API, MCP tools and model registry page

- [x] 15. Maintenance investigation agent
  - [x] 15.1 Nine allowlisted MCP tools with evidence-class labelled results
  - [x] 15.2 Report generation from Java-collected evidence; agent interpretation stored as AI_INTERPRETATION with citation check
  - [x] 15.3 Maintenance drafts that must cite evidence, capped per investigation, linked to the tool call
  - [x] 15.4 `investigate_machine` MCP prompt and Kiro playbook steering
  - [x] 15.5 Agent policy: allowlist, per-tool access, input limits, rate limit, timeouts, DENIED audit outcome
  - [x] 15.6 PDF page references in chunks and citations
  - _Requirements: 10, 10a, 10b_

- [x] 16. MCP integration
  - [x] 16.1 Separate ordinary tool calling (`AgentToolset`) from MCP publication (`McpToolExposure`)
  - [x] 16.2 Namespaced MCP names compatible with Kiro, titles and behaviour hints
  - [x] 16.3 Stand-alone Java MCP client and MCP Inspector script; discovery integration test over real HTTP
  - _Requirements: 10c_

- [x] 17. Frontend: six screens
  - [x] 17.1 Factory overview KPIs, risk summary, active alerts, recent maintenance
  - [x] 17.2 Machine monitoring: connectivity and data-quality calculator, historical readings
  - [x] 17.3 Predictive analytics page with prediction history chart, contributions, model performance and importance
  - [x] 17.4 Report page (formerly the Kiro assistant page); investigation page with evidence map, linked citations, tool trace, limitations
  - [x] 17.4a Kiro assistant page: conversation with Kiro CLI run by the server per message, with a per-run MCP token scoped to the engineer
  - [x] 17.5 Maintenance management with technician role, assignment, completion rules, approvals and audit trail
  - [x] 17.6 Knowledge management with document detail, sections, page numbers and citing investigations
  - [x] 17.7 V11 migration (evidence → document section, investigation → tool call, work-order assignment); UI test of all screens

- [x] 18. Testing and quality engineering
  - [x] 18.1 Sensor faults separated from anomalies; per-reading outcomes; event-id idempotency (V12)
  - [x] 18.2 Confirmed failures recorded only by people, linked to the prior prediction
  - [x] 18.3 503 + Retry-After on database outage; graceful degradation when model files are missing
  - [x] 18.4 Agent write tools atomic with a deadline; idempotent drafts
  - [x] 18.5 Scenario tests for all eleven failure cases; PostgreSQL repository and Toxiproxy outage tests; WireMock client tests; ML quality gate; load test; real Mako outage script
  - [x] 18.6 docs/testing.md

- [ ] 14. Next steps (not started)
  - [ ] 14.1 Replace synthetic risk training data with real maintenance history when available
  - [ ] 14.2 Fine-tune a small CNN on a public defect dataset (e.g. MVTec AD) and export to ONNX with the same contract
  - [ ] 14.3 pgvector hybrid retrieval, only after verifying a free embedding model that runs on the laptop CPU
  - [ ] 14.4 Validate the Mako Server edge app on real hardware with Modbus TCP sensors
