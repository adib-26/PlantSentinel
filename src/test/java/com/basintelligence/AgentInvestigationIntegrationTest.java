package com.basintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.agent.AgentToolPolicy;
import com.basintelligence.agent.AgentToolset;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The example investigation from the agent design, driven through ordinary in-process tool calling (the same
 * callbacks the MCP server publishes, with policy, timeout and audit): "Why is machine M001 showing a high failure risk?"
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentInvestigationIntegrationTest {

    @Autowired AgentToolset tools;
    @Autowired MachineService machines;
    @Autowired TelemetryService telemetry;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @BeforeAll
    void plant() {
        var m = machines.register(new MachineService.RegisterMachine("M001", "Spindle mill", MachineType.CNC_MILL, "Hall 1"), "test");
        Instant start = Instant.now().minus(Duration.ofMinutes(119)).truncatedTo(ChronoUnit.SECONDS);
        var generator = new TelemetryGenerator(new Random(3), Duration.ofMinutes(120), 0);
        for (int i = 0; i < 120; i++) {
            telemetry.ingest(machines.get(m.id()), generator.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR,
                    start, start.plus(Duration.ofMinutes(i))));
        }
    }

    @Test
    void publishesExactlyTheNineAllowlistedTools() {
        Set<String> names = tools.callbacks().stream().map(t -> t.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertThat(names).isEqualTo(AgentToolPolicy.TOOLS.keySet());
        assertThat(names).noneMatch(n -> n.toLowerCase().contains("approve"));
    }

    @Test
    void exampleInvestigationSeparatesEvidenceAndEndsInAHumanApproval() throws Exception {
        JsonNode status = call("getMachineStatus", Map.of("machineCode", "m001")); // case-insensitive input
        assertThat(status.at("/0/code").asText()).isEqualTo("M001");
        assertThat(status.at("/0/dataFreshness").asText()).startsWith("live");

        JsonNode readings = call("getSensorReadings", Map.of("machineCode", "M001", "windowMinutes", 120, "maxPoints", 20));
        assertThat(readings.at("/summary/evidenceClass").asText()).isEqualTo("MEASURED");
        assertThat(readings.at("/readings/data")).hasSize(20);

        JsonNode anomaly = call("detectAnomaly", Map.of("machineCode", "M001"));
        assertThat(anomaly.at("/detectedEvents/evidenceClass").asText()).isEqualTo("RULE_BASED");
        assertThat(anomaly.at("/multivariateModel/evidenceClass").asText()).isEqualTo("MODEL_PREDICTION");
        assertThat(anomaly.at("/faultSignatures/data/0/code").asText()).isEqualTo("BEARING_WEAR");

        JsonNode risk = call("predictFailureRisk", Map.of("machineCode", "M001"));
        assertThat(risk.at("/prediction/evidenceClass").asText()).isEqualTo("MODEL_PREDICTION");
        assertThat(risk.at("/prediction/data/probability").asDouble()).isGreaterThan(0.5);
        assertThat(risk.at("/limitations/0").asText()).contains("not on this plant");

        assertThat(call("getHistoricalFailures", Map.of("machineCode", "M001")).at("/incidents/evidenceClass").asText())
                .isEqualTo("DOCUMENTED");
        assertThat(call("getMachineSpecifications", Map.of("machineCode", "M001")).at("/operatingEnvelope/data")).hasSize(5);

        JsonNode manual = call("searchMaintenanceManual", Map.of("query", "spindle bearing vibration rising", "machineCode", "M001"));
        assertThat(manual.at("/passages/data/0/citation").asText()).contains("›");

        JsonNode report = call("generateMaintenanceReport", Map.of("machineCode", "M001",
                "question", "Why is machine M001 showing a high failure risk?",
                "interpretation", "Vibration rose steadily [E2] while temperature followed [E1], which matches the bearing "
                        + "wear pattern in the manual. The risk model agrees [E4]. This is my inference, see also [E99]."));
        long investigationId = report.get("investigationId").asLong();
        String md = report.get("reportMarkdown").asText();
        assertThat(md).contains("## Measured", "## Rule-based detection", "## Model prediction", "## Documented",
                "## AI interpretation", "No maintenance action has been approved");
        assertThat(md.indexOf("## Measured")).isLessThan(md.indexOf("## AI interpretation"));
        assertThat(report.get("unverifiedCitations").toString()).contains("E99");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM recommendations WHERE investigation_id = :i").param("i", investigationId)
                .query(Long.class).single()).isZero(); // the report alone drafts nothing

        assertThatThrownBy(() -> call("createMaintenanceDraft", Map.of("investigationId", investigationId,
                "action", "Replace the spindle bearings", "rationale", "Because I say so", "priority", "HIGH")))
                .hasMessageContaining("evidence");
        JsonNode draft = call("createMaintenanceDraft", Map.of("investigationId", investigationId,
                "action", "Inspect and replace the spindle bearings at the next planned stop",
                "rationale", "Vibration trend E2 and model risk E4; manual section on bearing replacement.", "priority", "HIGH"));
        assertThat(draft.get("status").asText()).isEqualTo("PROPOSED");

        // Only a human engineer can approve. The draft is linked to the exact tool call that created it.
        long recommendationId = draft.get("recommendationId").asLong();
        assertThat(jdbc.sql("""
                SELECT t.tool_name FROM recommendations r JOIN agent_tool_audit t ON t.id = r.proposed_via_tool_call
                WHERE r.id = :r""").param("r", recommendationId).query(String.class).single())
                .isEqualTo("createMaintenanceDraft");
        mvc.perform(post("/api/recommendations/{id}/approve", recommendationId).with(httpBasic("engineer", "engineer-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Confirmed by vibration spectrum\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_tool_audit WHERE status = 'SUCCESS'").query(Long.class).single())
                .isGreaterThanOrEqualTo(9);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_predictions WHERE trigger_type = 'AGENT'").query(Long.class).single())
                .isPositive();
    }

    @Test
    void rejectsMalformedInputs() {
        assertThatThrownBy(() -> call("getSensorReadings", Map.of("machineCode", "../../etc/passwd")))
                .hasMessageContaining("machineCode");
        assertThatThrownBy(() -> call("searchMaintenanceManual", Map.of("query", " "))).hasMessageContaining("query");
    }

    private JsonNode call(String tool, Map<String, Object> args) throws Exception {
        ToolCallback callback = tools.callbacks().stream()
                .filter(t -> t.getToolDefinition().name().equals(tool)).findFirst().orElseThrow();
        return json.readTree(callback.call(json.writeValueAsString(args)));
    }
}
