package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.common.ActivityTracker;
import com.basintelligence.common.BasMetrics;
import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.ml.MlPredictionLog;
import com.basintelligence.ml.MlPredictionService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryRepository;
import com.basintelligence.telemetry.TelemetrySource;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * "Stored predictions outgrow the free-plan database." The hourly jobs delete predictions older than
 * {@code bas.predictions.retention} / {@code ml-retention}, but never a failure-risk prediction that an investigation
 * or a confirmed failure cites. The row limits are covered by {@link PredictionRowLimitScenarioIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PredictionRetentionScenarioIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryRepository telemetry;
    @Autowired PredictionService predictions;
    @Autowired MlPredictionService ml;
    @Autowired ActivityTracker activity;
    @Autowired BasProperties properties;
    @Autowired MeterRegistry meters;
    @Autowired JdbcClient jdbc;

    @Test
    void oldPredictionsAreDeletedExceptThoseAnInvestigationOrAConfirmedFailureCites() throws Exception {
        Machine m = machineWithReadings("RET-01");
        long expired = predict(m), cited = predict(m), failedAfter = predict(m), recent = predict(m);
        Instant old = Instant.now().minus(properties.predictions().retention()).minus(Duration.ofDays(1));
        for (long id : List.of(expired, cited, failedAfter)) {
            backdate("model_predictions", id, old);
        }
        jdbc.sql("""
                INSERT INTO investigations (device_id, prediction_id, status, requested_by, created_at)
                VALUES (:d, :p, 'CLOSED', 'engineer', :at)""").param("d", m.id()).param("p", cited)
                .param("at", Text.utc(old)).update();
        jdbc.sql("""
                INSERT INTO failure_events (device_id, occurred_at, failure_mode, description, confirmed_by, confirmed_at,
                    prior_prediction_id)
                VALUES (:d, :at, 'BEARING', 'Outer race spalling found on inspection', 'engineer', :at, :p)""")
                .param("d", m.id()).param("p", failedAfter).param("at", Text.utc(old.plus(Duration.ofHours(2)))).update();

        var caller = new MlPredictionLog.Caller(m.id(), "engineer");
        var input = new MlPredictionService.ProcessInput('M', 298.1, 308.6, 1551, 42.8, 0);
        long mlExpired = ml.predictProcessFailure(List.of(input), caller).getFirst().predictionId();
        long mlRecent = ml.predictProcessFailure(List.of(input), caller).getFirst().predictionId();
        backdate("ml_predictions", mlExpired,
                Instant.now().minus(properties.predictions().mlRetention()).minus(Duration.ofDays(1)));

        double removedBefore = removed("model_predictions") + removed("ml_predictions");
        activity.touch(); // the jobs skip their run while nobody uses the app
        predictions.applyRetention();
        ml.applyRetention();

        assertThat(exists("model_predictions", expired)).isFalse();
        assertThat(exists("model_predictions", cited)).as("cited by an investigation").isTrue();
        assertThat(exists("model_predictions", failedAfter)).as("the prediction before a confirmed failure").isTrue();
        assertThat(exists("model_predictions", recent)).isTrue();
        assertThat(exists("ml_predictions", mlExpired)).isFalse();
        assertThat(exists("ml_predictions", mlRecent)).isTrue();
        assertThat(removed("model_predictions") + removed("ml_predictions")).isGreaterThanOrEqualTo(removedBefore + 2);

        // the links survive, and a deleted prediction can no longer be checked
        assertThat(jdbc.sql("SELECT prediction_id FROM investigations WHERE device_id = :d").param("d", m.id())
                .query(Long.class).single()).isEqualTo(cited);
        assertThat(jdbc.sql("SELECT prior_prediction_id FROM failure_events WHERE device_id = :d").param("d", m.id())
                .query(Long.class).single()).isEqualTo(failedAfter);
        mvc.perform(get("/api/machines/RET-01/predictions/" + expired + "/verification")
                .with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/RET-01/predictions/" + cited + "/verification")
                .with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isOk());
    }

    private long predict(Machine m) {
        return predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();
    }

    /** Table names are constants of this test, never input. */
    private void backdate(String table, long id, Instant at) {
        jdbc.sql("UPDATE " + table + " SET predicted_at = :at WHERE id = :id").param("at", Text.utc(at)).param("id", id)
                .update();
    }

    private boolean exists(String table, long id) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = :id").param("id", id).query(Long.class).single() == 1;
    }

    private double removed(String table) {
        Counter c = meters.find(BasMetrics.PREDICTIONS_REMOVED).tag("table", table).tag("reason", "retention").counter();
        return c == null ? 0 : c.count();
    }

    private Machine machineWithReadings(String code) {
        Machine m = machines.register(new MachineService.RegisterMachine(code, code, MachineType.CNC_MILL, null), "test");
        Instant t0 = Instant.now().minus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(code.hashCode()), Duration.ofMinutes(30), 0);
        for (int i = 0; i < 30; i++) {
            TelemetryReading r = gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, t0, t0.plus(Duration.ofMinutes(i)));
            telemetry.insert(new TelemetryReading(r.machineId(), r.recordedAt(), r.temperature(), r.vibration(), r.rpm(),
                    r.torque(), r.pressure(), TelemetrySource.EDGE));
        }
        return machines.get(m.id());
    }
}
