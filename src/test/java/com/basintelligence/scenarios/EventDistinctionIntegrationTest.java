package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.common.InvalidStateException;
import com.basintelligence.failure.FailureEventService;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.risk.RiskLevel;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;

/**
 * Sensor failure, machine anomaly, model prediction and confirmed failure are four different events. A high
 * predicted risk never proves a failure; only a person who inspected the machine can record one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventDistinctionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryService telemetry;
    @Autowired PredictionService predictions;
    @Autowired InvestigationService investigations;
    @Autowired MaintenanceService maintenance;
    @Autowired FailureEventService failures;
    @Autowired JdbcClient jdbc;

    @Test
    void highRiskPredictionAndAnomaliesDoNotCreateAFailure() {
        Machine m = degradingMachine("DIST-01");
        predictions.predict(m, PredictionService.Trigger.SCHEDULED, null);

        var latest = predictions.recent(m.id(), 1).getFirst();
        assertThat(latest.level().ordinal()).isGreaterThanOrEqualTo(RiskLevel.HIGH.ordinal());
        assertThat(rows("anomaly_events", m)).isPositive();
        assertThat(rows("failure_events", m)).isZero();
        assertThat(rows("sensor_faults", m)).isZero(); // abnormal but valid readings are not sensor faults
    }

    @Test
    void agentAndAutomationCannotConfirmFailuresInCodeOrInTheDatabase() {
        Machine m = degradingMachine("DIST-02");
        for (String actor : new String[] {"kiro-agent", "bas-diagnostics", "system"}) {
            assertThatThrownBy(() -> failures.confirm(m.id(), FailureEventService.FailureMode.BEARING, "x", null, null, actor))
                    .isInstanceOf(InvalidStateException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO failure_events (device_id, occurred_at, failure_mode, description, confirmed_by, confirmed_at)
                VALUES (:d, CURRENT_TIMESTAMP, 'BEARING', 'x', 'kiro-agent', CURRENT_TIMESTAMP)""").param("d", m.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void technicianConfirmsFailureWhenCompletingWorkAndItLinksTheEarlierPrediction() throws Exception {
        Machine m = degradingMachine("DIST-03");
        var view = investigations.investigate(m.id(), "risk?", "engineer");
        long rec = view.recommendations().getFirst().id();
        investigations.review(rec, true, "ok", "engineer2");
        long work = maintenance.forRecommendations(java.util.List.of(rec)).getFirst().id();
        maintenance.assign(work, "technician", "engineer");

        mvc.perform(post("/maintenance/" + work + "/complete").with(user("technician").roles("TECHNICIAN", "VIEWER")).with(csrf())
                        .param("notes", "Outer race spalling on DE bearing").param("confirmedFailure", "BEARING"))
                .andExpect(status().is3xxRedirection());

        var f = failures.forMachine(m.id(), 1).getFirst();
        assertThat(f.confirmedBy()).isEqualTo("technician");
        assertThat(f.maintenanceRecordId()).isEqualTo(work);
        assertThat(f.investigationId()).isEqualTo(view.investigation().id());
        assertThat(f.priorPredictionId()).isNotNull();
        assertThat(f.priorProbability()).isGreaterThan(0.5);
    }

    @Test
    void viewersCannotConfirmFailuresButEngineersCanViaTheApi() throws Exception {
        Machine m = degradingMachine("DIST-04");
        String body = "{\"mode\":\"COOLING\",\"description\":\"Fan seized\"}";
        mvc.perform(post("/api/machines/DIST-04/failures").with(httpBasic("viewer", "viewer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/machines/DIST-04/failures").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/machines/DIST-04/failures").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MELTDOWN\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest());
        assertThat(rows("failure_events", m)).isEqualTo(1);
    }

    private Machine degradingMachine(String code) {
        Machine m = machines.register(new MachineService.RegisterMachine(code, code, MachineType.CNC_MILL, null), "test");
        Instant start = Instant.now().minus(Duration.ofMinutes(119)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(2), Duration.ofMinutes(100), 0);
        for (int i = 0; i < 120; i++) {
            telemetry.ingest(machines.get(m.id()), gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, start,
                    start.plus(Duration.ofMinutes(i))));
        }
        return machines.get(m.id());
    }

    private long rows(String table, Machine m) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE device_id = :d").param("d", m.id()).query(Long.class).single();
    }
}
