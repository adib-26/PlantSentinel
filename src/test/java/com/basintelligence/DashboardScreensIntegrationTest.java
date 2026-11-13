package com.basintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.vision.InspectionService;
import com.basintelligence.vision.SampleImageGenerator;
import com.basintelligence.web.ui.Formatters;

/** Renders the six dashboard screens with real data and checks the AI evidence is visible and linked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardScreensIntegrationTest {

    private static final RequestPostProcessor ENGINEER = user("engineer").roles("ENGINEER", "VIEWER");
    private static final RequestPostProcessor ENGINEER2 = user("engineer2").roles("ENGINEER", "VIEWER");
    private static final RequestPostProcessor TECHNICIAN = user("technician").roles("TECHNICIAN", "VIEWER");
    private static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryService telemetry;
    @Autowired InspectionService inspections;
    @Autowired InvestigationService investigations;
    @Autowired MaintenanceService maintenance;
    @Autowired Formatters fmt;

    long investigationId;
    long recommendationId;

    @BeforeAll
    void plant() {
        var m = machines.register(new MachineService.RegisterMachine("UI-01", "UI mill", MachineType.CNC_MILL, "Hall 9"), "test");
        Instant start = Instant.now().minus(Duration.ofMinutes(119)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(9), Duration.ofMinutes(120), 0);
        for (int i = 0; i < 120; i++) {
            telemetry.ingest(machines.get(m.id()), gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, start,
                    start.plus(Duration.ofMinutes(i))));
        }
        inspections.inspectSample(machines.get(m.id()), "Bearing housing", SampleImageGenerator.Kind.CRACK, "engineer");
        var view = investigations.investigate(m.id(), "Why is UI-01 risky?", "engineer");
        investigationId = view.investigation().id();
        recommendationId = view.recommendations().getFirst().id();
    }

    @Test
    void screen1FactoryOverview() throws Exception {
        assertThat(page("/", VIEWER)).contains("Registered machines", "Operating normally", "Need attention",
                "Active alerts (1 h)", "Recent maintenance events", "Factory risk summary", "UI-01");
    }

    @Test
    void screen2MachineMonitoring() throws Exception {
        assertThat(page("/machines/UI-01", VIEWER)).contains("Connectivity and data quality", "Completeness",
                "Historical readings", "data-metric=\"VIBRATION\"", "data-metric=\"TORQUE\"", "Source");
    }

    @Test
    void screen3PredictiveAnalytics() throws Exception {
        assertThat(page("/analytics?machine=UI-01", VIEWER)).contains("Isolation Forest score", "Failure-risk model",
                "Hold-out ROC-AUC", "Feature importance", "Prediction history: UI-01", "failure-risk-lr v1",
                "process-failure-classifier");
        assertThat(page("/analytics/UI-01/predictions.json", VIEWER)).contains("\"probability\"", "INVESTIGATION");
    }

    @Test
    void screen4AssistantAndEvidenceTrace() throws Exception {
        assertThat(page("/assistant", VIEWER)).contains("Investigation history", "INV-" + investigationId,
                "Two ways to get an answer", "pipeline · engineer");
        String inv = page("/investigations/" + investigationId, VIEWER);
        assertThat(inv).contains("Evidence map", "Confidence and limitations", "Tool execution trace", "built-in Java pipeline",
                "Measured", "Model prediction", "Documented");
        // [E1]-style citations become links, and manual evidence opens the exact document section
        assertThat(inv).contains("<a class=\"cite\" href=\"#E");
        assertThat(inv).containsPattern("href=\"/manuals/\\d+#chunk-\\d+\"");
        mvc.perform(post("/assistant/ask").with(ENGINEER).with(csrf()).param("question", "Is ui-01 ok?"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/assistant/ask").with(VIEWER).with(csrf()).param("question", "Is UI-01 ok?"))
                .andExpect(status().isForbidden());
    }

    @Test
    void screen5MaintenanceApprovalAssignmentAndCompletion() throws Exception {
        assertThat(page("/maintenance", ENGINEER2)).contains("Pending approvals", "Work orders", "Incident reports",
                "Approval audit trail");
        mvc.perform(post("/recommendations/" + recommendationId + "/review").with(ENGINEER2).with(csrf())
                .param("approve", "true").param("investigationId", String.valueOf(investigationId))
                .param("returnTo", "/maintenance")).andExpect(status().is3xxRedirection());
        long workOrder = maintenance.forRecommendations(java.util.List.of(recommendationId)).getFirst().id();

        // A technician cannot see (404) or complete work on a machine they are not assigned to, and cannot assign.
        mvc.perform(post("/maintenance/" + workOrder + "/complete").with(TECHNICIAN).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/maintenance/" + workOrder + "/assign").with(TECHNICIAN).with(csrf()).param("technician", "technician"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/maintenance/" + workOrder + "/assign").with(ENGINEER).with(csrf()).param("technician", "technician"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/maintenance/" + workOrder + "/complete").with(TECHNICIAN).with(csrf())
                .param("notes", "Replaced DE bearing").param("returnTo", "https://evil.example"))
                .andExpect(status().is3xxRedirection())
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/maintenance"));
        assertThat(maintenance.get(workOrder).completedBy()).isEqualTo("technician");
        assertThat(page("/maintenance", VIEWER)).contains("MAINTENANCE_ASSIGNED", "MAINTENANCE_COMPLETED", "engineer2");
    }

    @Test
    void screen6KnowledgeManagement() throws Exception {
        String list = page("/manuals?q=spindle+bearing+vibration", VIEWER);
        assertThat(list).contains("Browse documents", "Search technical information").doesNotContain("Upload maintenance manuals");
        assertThat(page("/manuals", ENGINEER)).contains("Upload maintenance manuals");
        assertThat(list).containsPattern("href=\"/manuals/\\d+#chunk-\\d+\"");
        var m = java.util.regex.Pattern.compile("href=\"/manuals/(\\d+)\"").matcher(list);
        assertThat(m.find()).isTrue();
        assertThat(page("/manuals/" + m.group(1), VIEWER)).contains("Extracted sections", "Document references");
    }

    @Test
    void citationLinksEscapeEverythingElse() {
        assertThat(fmt.cite("Vibration rose [E2] <script>x</script> [E1, E4]"))
                .contains("[<a class=\"cite\" href=\"#E2\">E2</a>]", "&lt;script&gt;", "<a class=\"cite\" href=\"#E4\">E4</a>")
                .doesNotContain("<script>");
    }

    private String page(String url, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
