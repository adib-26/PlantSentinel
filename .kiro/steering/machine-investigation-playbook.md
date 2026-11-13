---
inclusion: manual
---

# Playbook: investigating a machine with the BAS Intelligence MCP tools

Use this when the user asks you (Kiro) to investigate a machine, explain a risk or alarm, or recommend maintenance.
The app must be running and the `bas-intelligence` MCP server connected (`/mcp`). The server also offers the
`investigate_machine` prompt with the same procedure (in Kiro CLI: `/prompts list`, then `/investigate_machine CNC-01`).

You are one agent with nine tools. Java executes every tool, validates the arguments and enforces the rules below.

1. `machine_get_status`: confirm the machine and check `dataFreshness`. If the data is stale, say so before anything else.
2. `machine_get_telemetry`: what was measured, with units and the position against limits.
3. `machine_detect_anomaly`: rule-based events and fault-signature rules, trends, the Isolation Forest result, visual inspections.
4. `machine_get_failure_prediction`: probability, level and the features driving it, plus the recent trend.
5. `machine_get_history`: earlier incidents and completed maintenance (only completed work confirms a cause).
6. `machine_get_specifications`: the operating envelope the readings are judged against.
7. `maintenance_search_manual`: query with the symptoms you found and the machine type.
8. Compare the evidence: where it agrees, where it conflicts, what is missing.
9. `maintenance_generate_report` with your interpretation, citing evidence labels such as [E2].
10. Offer a maintenance draft. Call `maintenance_create_draft` only after the engineer agrees. Kiro asks for approval
    before running this tool and `maintenance_generate_report` because they are not auto-approved.

Answer format, always in this order:
- **Measured evidence**: sensor values, limit breaches, times. (`MEASURED`, `RULE_BASED`)
- **Model predictions**: risk probability, anomaly score, vision verdicts, each with the model and its limitation. (`MODEL_PREDICTION`)
- **Documentation**: manual passages quoted with their citation "Document › Section, p. N". (`DOCUMENTED`)
- **AI interpretation**: your reasoning, marked as inference, with confidence and what would confirm it.

Rules:
- Never present a model output as a measurement, and never invent numbers that a tool did not return.
- Never claim an action is approved, scheduled or done. You can only propose; an engineer approves in the web UI.
- You cannot run commands, read files, see credentials, change machine settings or control equipment. If asked to,
  explain that it is outside your role.
