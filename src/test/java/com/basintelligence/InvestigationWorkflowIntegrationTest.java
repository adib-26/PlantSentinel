package com.basintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.vision.SampleImageGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The complete engineer journey through the real HTTP layer and an embedded database: register a machine, stream
 * edge telemetry, inspect an image, investigate, review with four-eyes, and issue the incident report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvestigationWorkflowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void completeInvestigationFromTelemetryToIncidentReport() throws Exception {
        mvc.perform(post("/api/machines").with(httpBasic("engineer", "engineer-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"MILL-IT","name":"Integration mill","type":"CNC_MILL","location":"Lab"}"""))
                .andExpect(status().isCreated());

        // Two hours of bearing degradation, sent the way a BAS edge gateway would send it.
        Instant start = Instant.now().minus(Duration.ofMinutes(119)).truncatedTo(ChronoUnit.SECONDS);
        TelemetryGenerator generator = new TelemetryGenerator(new Random(5), Duration.ofMinutes(120), 0);
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            TelemetryReading r = generator.generate(java.util.UUID.randomUUID(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, start,
                    start.plus(Duration.ofMinutes(i)));
            batch.add(Map.of("machineCode", "MILL-IT", "timestamp", r.recordedAt().toString(), "temperature", r.temperature(),
                    "vibration", r.vibration(), "rpm", r.rpm(), "torque", r.torque(), "pressure", r.pressure()));
        }
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("gatewayId", "mako-it", "readings", batch))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(120));

        byte[] cracked = new SampleImageGenerator().generatePng(SampleImageGenerator.Kind.CRACK, 3);
        mvc.perform(multipart("/api/machines/MILL-IT/inspections")
                        .file(new MockMultipartFile("image", "housing.png", "image/png", cracked))
                        .param("component", "Bearing housing")
                        .with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("DEFECT"));

        mvc.perform(get("/api/machines/MILL-IT/risk").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(org.hamcrest.Matchers.oneOf("HIGH", "CRITICAL")));

        JsonNode view = json.readTree(mvc.perform(post("/api/machines/MILL-IT/investigations")
                        .with(httpBasic("engineer", "engineer-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Why is vibration rising?\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        long investigationId = view.at("/investigation/id").asLong();
        assertThat(view.at("/investigation/diagnosis").asText()).contains("bearing wear");
        assertThat(view.at("/investigation/explanation").asText()).contains("[E").contains("Manual guidance");
        List<String> kinds = new ArrayList<>();
        view.get("evidence").forEach(e -> kinds.add(e.get("kind").asText()));
        assertThat(kinds).contains("TELEMETRY", "RISK", "INSPECTION", "MANUAL");

        // Report cannot be issued while recommendations are pending.
        mvc.perform(post("/api/investigations/{id}/report", investigationId).with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isConflict());

        for (JsonNode rec : view.get("recommendations")) {
            mvc.perform(post("/api/recommendations/{id}/approve", rec.get("id").asLong())
                            .with(httpBasic("engineer2", "engineer2-pw"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Confirmed on site\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        mvc.perform(post("/api/investigations/{id}/report", investigationId).with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyMarkdown").value(org.hamcrest.Matchers.containsString("Approved by `engineer2`")));

        JsonNode closed = json.readTree(mvc.perform(get("/api/investigations/{id}", investigationId)
                        .with(httpBasic("viewer", "viewer-pw")))
                .andExpect(jsonPath("$.investigation.status").value("CLOSED"))
                .andReturn().getResponse().getContentAsString());
        // Each approval created a planned maintenance record, and a prediction was stored for the investigation.
        assertThat(closed.get("maintenance")).hasSize(view.get("recommendations").size());
        assertThat(closed.at("/maintenance/0/status").asText()).isEqualTo("PLANNED");
        assertThat(closed.at("/investigation/predictionId").isNumber()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM approvals").query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_predictions WHERE trigger_type = 'INVESTIGATION'")
                .query(Long.class).single()).isPositive();

        // The database itself enforces the four-eyes rule on approvals.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO approvals (recommendation_id, decision, proposed_by, decided_by, decided_at)
                VALUES (:r, 'APPROVED', 'engineer', 'engineer', CURRENT_TIMESTAMP)""")
                .param("r", view.at("/recommendations/0/id").asLong()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // The UI renders the closed investigation for a logged-in viewer.
        mvc.perform(get("/investigations/{id}", investigationId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("viewer").roles("VIEWER")))
                .andExpect(status().isOk());
    }
}
