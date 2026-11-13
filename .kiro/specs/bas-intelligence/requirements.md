# Requirements: BAS Intelligence

## Introduction

BAS Intelligence is a locally run Spring Boot application that monitors industrial machines, detects abnormal behaviour, estimates failure risk, inspects component images, and runs evidence-based investigations that end in human-approved maintenance actions and an incident report. Kiro acts as the reasoning agent through an MCP server. Constraints: Java 21, 8 GB laptop, CPU only, no cloud application hosting, RM0 recurring cost.

## Requirement 1: Machine registry

**User story:** As an engineer, I want to register and list machines so that their telemetry can be monitored.

1. WHEN an engineer submits a machine code, name, type and location THEN the system SHALL store the machine with health status UNKNOWN.
2. IF the code is already registered THEN the system SHALL reject the request with HTTP 409.
3. IF the code does not match `[A-Z0-9][A-Z0-9-]{1,39}` THEN the system SHALL reject the request with a validation error.
4. WHEN a viewer attempts to register a machine THEN the system SHALL respond with HTTP 403.
5. The system SHALL associate each machine type with an operating envelope (nominal, warning and critical limits) for temperature, vibration, speed, torque and pressure.

## Requirement 2: Edge telemetry ingestion

**User story:** As a BAS edge gateway, I want to post batches of readings so that the server always has current machine data.

1. WHEN a gateway posts a batch of up to 500 readings with a valid edge API key THEN the system SHALL persist each reading and respond HTTP 202 with the resulting health per machine.
2. IF the X-API-Key header is missing or wrong, or is the agent key, THEN the system SHALL respond HTTP 401.
3. IF a reading is outside physical plausibility bounds THEN the system SHALL reject the batch with HTTP 400.
4. IF a reading references an unknown machine THEN the system SHALL respond HTTP 404.
5. IF a reading timestamp is more than 5 minutes in the future THEN the system SHALL reject it.
6. WHILE no real edge data has arrived for a machine in the last 5 minutes AND the simulator is enabled, the system SHALL generate simulated readings for it.
7. The system SHALL delete readings and anomaly events older than the configured retention period.

## Requirement 3: Anomaly detection

**User story:** As an engineer, I want abnormal behaviour flagged automatically so that I notice problems early.

1. WHEN a reading breaches a warning or critical limit THEN the system SHALL record an ENVELOPE anomaly with that severity and set the machine health accordingly.
2. WHEN a reading deviates by at least the z-score threshold from the machine's last 60 readings (minimum 20) THEN the system SHALL record a ZSCORE anomaly.
3. The system SHALL suppress duplicate anomalies of the same machine, metric, detector and severity within 10 minutes.
4. WHEN a telemetry window has at least 60 readings THEN the system SHALL compute an Isolation Forest score for the latest readings against an adaptive threshold learned from the earlier part of the window.
5. The system SHALL report per-metric trend statistics: latest, mean, min, max, change versus window start, and slope per hour.

## Requirement 4: Failure-risk estimation

**User story:** As an engineer, I want a failure-risk estimate with reasons so that I can prioritise maintenance.

1. WHEN risk is requested for a machine with telemetry THEN the system SHALL return a probability, a level (LOW < 25% ≤ MEDIUM < 50% ≤ HIGH < 75% ≤ CRITICAL), and per-feature contributions to the log-odds.
2. The system SHALL train the model at startup on the CPU and report hold-out accuracy and AUC.
3. The system SHALL state in model metadata that the training data is simulated.

## Requirement 5: Visual inspection

**User story:** As an engineer, I want to upload a component photo so that defects such as cracks are detected.

1. WHEN an engineer uploads a PNG, JPEG or BMP image THEN the system SHALL preprocess it with OpenCV, run the ONNX defect model on the CPU, and store the verdict (PASS or DEFECT), probability, model version and inference time.
2. WHEN the verdict is DEFECT THEN the system SHALL localise candidate regions and store an annotated image.
3. IF the upload is not a decodable image or is outside 32 to 8192 pixels per side THEN the system SHALL reject it.
4. The system SHALL store images on local disk and serve annotated images only by inspection id.

## Requirement 6: Maintenance manual retrieval (RAG)

**User story:** As an engineer, I want relevant manual passages retrieved so that recommendations are grounded in documentation.

1. WHEN an engineer uploads a PDF, DOCX, DOC, TXT or MD file THEN the system SHALL extract text with Apache Tika, split it into section-aware chunks of at most 900 characters, store them, and rebuild the retrieval index.
2. IF the same file content was uploaded before THEN the system SHALL reject it with HTTP 409.
3. WHEN a search is made THEN the system SHALL return BM25-ranked passages with document title and section as the citation, optionally restricted to a machine type plus general manuals.
4. The system SHALL seed the bundled manuals at startup.

## Requirement 7: Investigation

**User story:** As an engineer, I want to ask the system to investigate a machine so that I receive a cited explanation and a recommendation.

1. WHEN an investigation is started THEN the system SHALL collect telemetry trends, multivariate score, anomalies, risk, recent inspections and manual passages as labelled evidence (E1, E2, ...).
2. The system SHALL rank fault hypotheses (bearing wear, cooling degradation, overload, pressure loss, speed fault, surface defect, unclassified deviation, no fault) with confidence values.
3. The system SHALL produce an explanation whose claims cite evidence labels, list alternative hypotheses, and state model limitations.
4. The system SHALL draft one PROPOSED recommendation for the primary hypothesis and one for an alternative with confidence ≥ 60%.
5. IF a machine has no telemetry in the analysis window THEN the system SHALL refuse to investigate with HTTP 409.

## Requirement 8: Human review (human in the loop)

**User story:** As an engineer, I want to approve or reject each recommendation so that nothing is actioned without accountable sign-off.

1. WHEN an engineer approves or rejects a PROPOSED recommendation THEN the system SHALL record the decision, reviewer, time and comment.
2. IF the reviewer is the proposer THEN the system SHALL reject the review.
3. IF the recommendation was already reviewed THEN the system SHALL respond HTTP 409.
4. WHEN no PROPOSED recommendations remain THEN the investigation SHALL become REVIEWED.
5. WHEN a new recommendation is proposed on a REVIEWED investigation THEN it SHALL return to OPEN.

## Requirement 9: Incident report

**User story:** As an engineer, I want an incident report so that the investigation is documented.

1. WHEN an engineer requests a report on a REVIEWED investigation THEN the system SHALL generate a Markdown report containing machine details, diagnosis, risk, findings, every decision with reviewer, and all evidence, then mark the investigation CLOSED.
2. IF recommendations are still pending THEN the system SHALL respond HTTP 409.
3. The report SHALL be downloadable as a `.md` file.

## Requirement 10: Maintenance investigation agent (Kiro over MCP)

**User story:** As an engineer, I want to ask Kiro "Why is machine M001 showing a high failure risk?" and get an
answer that separates what was measured, what a model predicted and what the AI concluded, ending in an approval
request I control.

1. The system SHALL provide one agent toolset over MCP with exactly these tools: getMachineStatus, getSensorReadings,
   detectAnomaly, predictFailureRisk, getHistoricalFailures, searchMaintenanceManual, getMachineSpecifications,
   generateMaintenanceReport and createMaintenanceDraft. Java SHALL execute every tool; the agent only requests them.
2. Every tool result SHALL label its parts as MEASURED, RULE_BASED, MODEL_PREDICTION, DOCUMENTED or AI_INTERPRETATION.
3. WHEN generateMaintenanceReport is called THEN the system SHALL collect the evidence itself, store the agent's text
   as AI_INTERPRETATION, flag citations to evidence labels that do not exist, and draft no recommendation.
4. WHEN createMaintenanceDraft is called THEN the system SHALL record a PROPOSED recommendation linked to the tool
   call, only if the rationale cites existing evidence labels and the agent has fewer than 3 open drafts.
5. The system SHALL provide an `investigate_machine` MCP prompt describing the investigation steps and answer format.

## Requirement 10c: MCP integration

1. The tools SHALL work through ordinary in-process tool calling (Spring AI tool callbacks) and SHALL be published
   over MCP from the same callbacks, so both paths share validation, policy and audit.
2. MCP tool names SHALL be namespaced (`machine_*`, `maintenance_*`), match `^[a-zA-Z][a-zA-Z0-9_]*$` and stay under
   40 characters so Kiro and other strict clients accept them.
3. Each MCP tool SHALL advertise a title and read-only and idempotent hints.
4. A separate MCP client, not Kiro, SHALL be able to connect with the agent key, discover the tools and prompt, and call
   them; a wrong key SHALL be refused.

## Requirement 10a: Agent security

1. The agent SHALL NOT be able to run shell commands, read files, access credentials, change machine settings,
   control machinery or approve recommendations. No tool or endpoint reachable with the agent key SHALL offer these.
2. The system SHALL refuse tools not on the allowlist and fail at startup if an unlisted tool is published.
3. The system SHALL validate tool inputs, limit argument size (8000 characters) and calls (60 per minute per session),
   and time out reads after 10 s and record tools after 30 s.
4. The system SHALL audit every tool call with session, arguments, outcome (SUCCESS, ERROR, DENIED) and duration.
5. Recording tools SHALL be switchable off without disabling the read tools.

## Requirement 10b: Retrieval with citations

1. Uploaded PDF and DOCX manuals SHALL be extracted with Apache Tika and split into sections.
2. For PDFs, each section SHALL record its page range; citations SHALL read "Document › Section, p. N".
3. Retrieval SHALL use PostgreSQL full-text search (BM25 offline). Vector search with pgvector is deferred until a
   free embedding model that fits the hardware is verified; a chat model is not assumed to provide embeddings.

## Requirement 11: Security, operations and documentation

1. The web UI SHALL use form login with CSRF protection and a Content Security Policy; the REST API SHALL use stateless HTTP Basic.
2. Health SHALL be public; metrics and the audit log SHALL require ADMIN.
3. The system SHALL publish Micrometer metrics for readings ingested, anomalies, inspections, inference time and investigation duration.
4. The system SHALL provide OpenAPI documentation at `/swagger-ui.html`.
5. The `neon` profile SHALL read all credentials from environment variables.
6. The application SHALL run with a 768 MB heap.
