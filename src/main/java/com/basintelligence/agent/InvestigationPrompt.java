package com.basintelligence.agent;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The {@code investigate_machine} MCP prompt: the agent's operating procedure for one investigation. Kiro (or any
 * MCP client) can load it to follow the same tool order and answer format.
 */
public final class InvestigationPrompt {

    public static final String NAME = "investigate_machine";

    static final String TEMPLATE = """
            You are the BAS Intelligence maintenance investigator. Investigate machine %s.
            Engineer's question: %s

            Follow these steps with the BAS Intelligence tools. Java executes every tool and enforces the rules.
            1. machine_get_status: confirm the machine exists and check data freshness. If data is stale, say so first.
            2. machine_get_telemetry: what was measured, against which limits.
            3. machine_detect_anomaly: rule-based detections, trends, fault-signature rules and the multivariate model.
            4. machine_get_failure_prediction: the model's estimate and which features drive it.
            5. machine_get_history: previous incidents and confirmed maintenance findings.
            6. machine_get_specifications: the operating envelope the readings are judged against.
            7. maintenance_search_manual: query with the symptoms you found and the machine type.
            8. Compare the evidence. Note where sources agree, where they conflict and what is missing.
            9. maintenance_generate_report with your interpretation, citing evidence labels like [E2].
            10. Offer a maintenance draft. Call maintenance_create_draft only after the engineer agrees.

            Answer in four labelled sections:
            - Measured evidence: sensor values and limit breaches, with units and times.
            - Model predictions: risk probability, anomaly score, vision results, each with its model and limitation.
            - Documentation: manual passages, quoted with their citation "Document › Section, p. N".
            - AI interpretation: your reasoning, clearly marked as inference, with confidence and what would confirm it.

            Rules: never present a model output as a measurement. Never claim an action is approved or done; you can
            only propose. You cannot change machine settings or control equipment. If evidence is insufficient, say so.
            """;

    private InvestigationPrompt() {
    }

    public static McpServerFeatures.SyncPromptSpecification specification() {
        var prompt = new McpSchema.Prompt(NAME, "Investigate a machine",
                "Step-by-step maintenance investigation that separates measured evidence, model predictions, "
                        + "documentation and AI interpretation",
                List.of(new McpSchema.PromptArgument("machineCode", "Machine code, e.g. CNC-01", true),
                        new McpSchema.PromptArgument("question", "The engineer's question", false)));
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            Object code = request.arguments() == null ? null : request.arguments().get("machineCode");
            Object question = request.arguments() == null ? null : request.arguments().get("question");
            String machine = code == null ? "(ask the engineer which machine)" : sanitise(code.toString(), 40);
            String q = question == null ? "Why is this machine showing its current failure risk?" : sanitise(question.toString(), 500);
            return new McpSchema.GetPromptResult("Machine investigation procedure", List.of(new McpSchema.PromptMessage(
                    McpSchema.Role.USER, new McpSchema.TextContent(TEMPLATE.formatted(machine, q)))));
        });
    }

    /** Prompt arguments come from the client; keep them short and single-line. */
    static String sanitise(String value, int max) {
        String s = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
