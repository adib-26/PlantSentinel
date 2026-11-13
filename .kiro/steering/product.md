# Product: BAS Intelligence

AI-powered industrial monitoring, predictive maintenance and diagnostic assistant for factory engineers.

## Users and goals
- **Engineer** (role ENGINEER): registers machines, uploads inspection images and manuals, runs investigations, approves or rejects maintenance recommendations, issues incident reports.
- **Viewer** (role VIEWER): read-only access to dashboards, investigations and reports.
- **Admin** (role ADMIN): everything above plus the audit log, Actuator metrics and manual deletion.
- **Kiro agent** (role AGENT over MCP): gathers evidence and *proposes* recommendations. It can never approve.
- **BAS edge gateway** (role EDGE): posts telemetry only.

## The core journey (must always work end to end)
1. Register a machine. 2. Telemetry arrives from a BAS / Mako Server edge node (or the simulator).
3. Streaming detectors flag anomalies. 4. The ML model estimates failure risk with explanations.
5. An engineer inspects a component image. 6. An engineer (or Kiro) starts an investigation.
7. Manual passages are retrieved as evidence. 8. A cited explanation is produced.
9. A different engineer approves or rejects each recommendation. 10. An incident report is issued.

## Product principles
- Explanations cite evidence labels (E1, E2, ...) and manual sections. Never present an unsupported claim.
- Human in the loop: no automated or agent action is executed without an engineer's decision. Four-eyes rule: proposers cannot review their own proposals.
- Be honest about limitations: the risk model is trained on simulated fault data and the vision model is a fixed-weight baseline. Say so in user-facing output.
- RM0 recurring cost: runs on an 8 GB laptop CPU. Only optional managed services are the Neon free-tier database and the agent's own model access.
