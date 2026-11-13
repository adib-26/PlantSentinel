package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.common.InvalidStateException;
import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.HealthStatus;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.IncidentSimulator;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;

/**
 * "An engineer simulates a machine incident": the fault ramps through normal ingestion, the detectors and health
 * status react, an investigation starts when the fault peaks, its recommendation still needs another engineer's
 * approval, and everything is labelled simulated. Real edge machines are refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulatedIncidentScenarioIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryService telemetry;
    @Autowired InvestigationService investigations;
    @Autowired IncidentSimulator incidents;

    @Test
    void simulatedBearingFailureRunsTheWholeJourney() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Machine machine = healthyHistory("SIM-01", now.minus(Duration.ofMinutes(6)));

        var incident = incidents.start(machine, Scenario.BEARING_WEAR, 5, now.minus(Duration.ofMinutes(6)), "engineer");
        assertThat(incidents.isActive(machine.id())).isTrue();
        assertThatThrownBy(() -> incidents.start(machines.get(machine.id()), Scenario.OVERLOAD, 5, now, "engineer"))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("already running");

        incidents.advance(now);

        var after = incidents.get(incident.id());
        assertThat(after.readings()).isGreaterThanOrEqualTo(36);
        assertThat(after.investigationId()).as("investigation starts when the fault peaks").isNotNull();
        assertThat(machines.get(machine.id()).healthStatus()).isIn(HealthStatus.WARNING, HealthStatus.CRITICAL);
        assertThat(telemetry.anomalies(machine.id(), Duration.ofMinutes(10), 50)).isNotEmpty();
        assertThat(telemetry.latest(machine.id(), 1).getFirst().source().name()).isEqualTo("SIMULATOR");

        var view = investigations.view(after.investigationId());
        assertThat(view.investigation().question()).contains("Simulated incident SIM-" + incident.id(), "BEARING_WEAR");
        assertThat(view.investigation().diagnosis().toLowerCase()).contains("bearing");
        var recommendation = view.recommendations().getFirst();
        assertThat(recommendation.status()).isEqualTo(RecommendationStatus.PROPOSED);

        // The human-in-the-loop rules still apply: another engineer approves, a work order is planned.
        mvc.perform(post("/api/recommendations/{id}/approve", recommendation.id()).with(httpBasic("engineer2", "engineer2-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

        String page = mvc.perform(get("/machines/SIM-01").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("SIMULATED INCIDENT", "SIM-" + incident.id(), "INV-" + after.investigationId());

        mvc.perform(post("/incidents/{id}/stop", incident.id()).with(user("engineer").roles("ENGINEER", "VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(incidents.get(incident.id()).status()).isEqualTo(IncidentSimulator.Status.STOPPED);
        assertThat(incidents.isActive(machine.id())).isFalse();
        mvc.perform(post("/api/incidents/{id}/stop", incident.id()).with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isConflict());
    }

    @Test
    void startingRequiresAnEngineerAValidFaultAndASimulatedMachine() throws Exception {
        healthyHistory("SIM-02", Instant.now().minus(Duration.ofMinutes(1)));
        String body = "{\"fault\":\"COOLING_DEGRADATION\",\"rampMinutes\":3}";
        mvc.perform(post("/api/machines/SIM-02/incidents").with(httpBasic("viewer", "viewer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/machines/SIM-02/incidents").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fault\":\"HEALTHY\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/machines/SIM-02/incidents").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fault\":\"OVERLOAD\",\"rampMinutes\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/machines/NOPE-99/incidents").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/machines/SIM-02/incidents").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.fault").value("COOLING_DEGRADATION"));

        // A machine fed by a real BAS edge node is never simulated.
        machines.register(new MachineService.RegisterMachine("SIM-EDGE", "Edge", MachineType.CNC_MILL, null), "test");
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"gatewayId":"g1","readings":[{"machineCode":"SIM-EDGE","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}"""))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/machines/SIM-EDGE/incidents").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("real data")));
    }

    private Machine healthyHistory(String code, Instant until) {
        Machine m = machines.register(new MachineService.RegisterMachine(code, code, MachineType.CNC_MILL, null), "test");
        var gen = new TelemetryGenerator(new Random(3), Duration.ofHours(6), 0);
        Instant start = until.minus(Duration.ofMinutes(90));
        for (Instant t = start; !t.isAfter(until); t = t.plus(Duration.ofMinutes(1))) {
            telemetry.ingest(machines.get(m.id()), gen.generate(m.id(), MachineType.CNC_MILL, Scenario.HEALTHY, start, t));
        }
        return machines.get(m.id());
    }
}
