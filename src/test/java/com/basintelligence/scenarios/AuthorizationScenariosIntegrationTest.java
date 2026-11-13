package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;

/** "A technician tries to approve an unauthorized request", and the other ways approval must not be bypassed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationScenariosIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryService telemetry;
    @Autowired InvestigationService investigations;

    long investigation;
    long recommendation;

    @BeforeAll
    void proposal() {
        var m = machines.register(new MachineService.RegisterMachine("AUTH-01", "Auth", MachineType.CNC_MILL, null), "test");
        Instant start = Instant.now().minus(Duration.ofMinutes(100)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(4), Duration.ofMinutes(100), 0);
        for (int i = 0; i < 100; i++) {
            telemetry.ingest(machines.get(m.id()), gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, start,
                    start.plus(Duration.ofMinutes(i))));
        }
        var view = investigations.investigate(m.id(), "q", "engineer");
        investigation = view.investigation().id();
        recommendation = view.recommendations().getFirst().id();
    }

    @Test
    void technicianCannotApproveOrRejectThroughApiOrWebUi() throws Exception {
        mvc.perform(post("/api/recommendations/{id}/approve", recommendation).with(httpBasic("technician", "technician-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/recommendations/{id}/reject", recommendation).with(httpBasic("technician", "technician-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/recommendations/{id}/review", recommendation).with(user("technician").roles("TECHNICIAN", "VIEWER"))
                .with(csrf()).param("approve", "true").param("investigationId", String.valueOf(investigation)))
                .andExpect(status().isForbidden());
        assertThat(status()).isNotNull();
        assertThat(investigations.view(investigation).recommendations().getFirst().status()).isEqualTo(RecommendationStatus.PROPOSED);
    }

    @Test
    void otherBypassAttemptsFail() throws Exception {
        // the agent's key does not work on the REST API
        mvc.perform(post("/api/recommendations/{id}/approve", recommendation).header("X-API-Key", "test-agent-key"))
                .andExpect(status().isUnauthorized());
        // the edge key does not work either
        mvc.perform(post("/api/recommendations/{id}/approve", recommendation).header("X-API-Key", "test-edge-key"))
                .andExpect(status().isUnauthorized());
        // viewers cannot approve
        mvc.perform(post("/api/recommendations/{id}/approve", recommendation).with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isForbidden());
        // web form without CSRF token
        mvc.perform(post("/recommendations/{id}/review", recommendation).with(user("engineer2").roles("ENGINEER", "VIEWER"))
                .param("approve", "true").param("investigationId", String.valueOf(investigation)))
                .andExpect(status().isForbidden());
        // the proposer cannot approve their own proposal (the pipeline proposed it, so use an agent-proposed one)
        var own = investigations.propose(investigation, "Check coupling", "See E1", com.basintelligence.investigation.InvestigationModels.Priority.LOW, "engineer");
        mvc.perform(post("/api/recommendations/{id}/approve", own.id()).with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isConflict());
        // a technician cannot assign work
        mvc.perform(post("/maintenance/1/assign").with(user("technician").roles("TECHNICIAN", "VIEWER")).with(csrf())
                .param("technician", "technician")).andExpect(status().isForbidden());
    }
}
