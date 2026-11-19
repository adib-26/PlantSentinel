package com.basintelligence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.ml.MlPredictionLog;
import com.basintelligence.ml.MlPredictionService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.risk.PredictionVerification;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryRepository;
import com.basintelligence.telemetry.TelemetryRollupService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.TelemetrySource;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.vision.InspectionService;
import com.basintelligence.vision.SampleImageGenerator;

/**
 * Repository tests on real PostgreSQL 17 (the engine Neon runs): Flyway migrations, SQL queries, constraints and
 * PostgreSQL-only full-text search. Skipped automatically without Docker. Run with {@code ./mvnw verify}. The
 * prediction row limits are at their minimum so the limit SQL runs; no other test here writes that many rows.
 */
@SpringBootTest(properties = {"bas.predictions.max-rows=1000", "bas.predictions.ml-max-rows=1000"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MlPredictionService ml;
    @Autowired MlPredictionLog mlLog;
    @Autowired MachineService machines;
    @Autowired TelemetryRepository telemetryRepository;
    @Autowired TelemetryService telemetry;
    @Autowired TelemetryRollupService rollups;
    @Autowired PredictionService predictions;
    @Autowired InspectionService inspections;
    @Autowired InvestigationService investigations;
    @Autowired ManualService manuals;

    @Test
    void allMigrationsApplyIncludingThePostgresOnlyOne() {
        assertThat(jdbc.sql("SELECT version()").query(String.class).single()).contains("PostgreSQL 17");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success").query(Long.class).single())
                .isGreaterThanOrEqualTo(13);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_chunks WHERE search_vector IS NULL").query(Long.class).single()).isZero();
    }

    @Test
    void fullTextSearchRanksTheRightSectionAndRespectsMachineType() {
        var hits = manuals.search("compressor discharge temperature too high cooler", MachineType.AIR_COMPRESSOR, 3);
        assertThat(manuals.retrieverName()).isEqualTo("PostgreSQL full-text search");
        assertThat(hits.getFirst().citation()).contains("High discharge temperature");
        assertThat(hits).allSatisfy(h -> assertThat(h.machineType()).isIn(null, MachineType.AIR_COMPRESSOR));
        assertThat(manuals.search("' OR 1=1 --", null, 3)).isNotNull(); // treated as words, never as SQL
    }

    @Test
    void telemetryQueriesRollupsAndEventIdUniqueness() {
        Machine m = machines.register(new MachineService.RegisterMachine("PG-01", "pg", MachineType.CNC_MILL, null), "test");
        Instant t0 = Instant.now().minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES);
        for (int i = 0; i < 30; i++) {
            telemetryRepository.insert(new TelemetryReading(m.id(), t0.plusSeconds(60L * i), 55, 1.8, 2400, 35, 6,
                    TelemetrySource.EDGE), "ev-" + i);
        }
        assertThat(telemetryRepository.findLatest(m.id(), 5)).hasSize(5)
                .extracting(TelemetryReading::recordedAt).isSorted();
        assertThat(telemetryRepository.existsEvent(m.id(), "ev-3")).isTrue();
        assertThatThrownBy(() -> telemetryRepository.insert(new TelemetryReading(m.id(), t0, 55, 1.8, 2400, 35, 6,
                TelemetrySource.EDGE), "ev-3")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rollups.rollup(m.id())).isGreaterThanOrEqualTo(5);
        assertThat(rollups.rollup(m.id())).isZero(); // idempotent
    }

    @Test
    void predictionGovernanceRecordViewAndVerificationOnPostgres() {
        Machine m = machines.register(new MachineService.RegisterMachine("PG-03", "pg", MachineType.CNC_MILL, null), "test");
        Instant t0 = Instant.now().minus(Duration.ofMinutes(30)); // sub-second timestamps: PostgreSQL keeps microseconds
        var gen = new TelemetryGenerator(new Random(3), Duration.ofMinutes(30), 0);
        for (int i = 0; i < 30; i++) {
            telemetryRepository.insert(gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, t0,
                    t0.plus(Duration.ofMinutes(i))));
        }
        long id = predictions.predict(machines.get(m.id()), PredictionService.Trigger.SCHEDULED, null).orElseThrow();
        byte[] image = new SampleImageGenerator().generatePng(SampleImageGenerator.Kind.GOOD, 3);
        long inspection = inspections.inspect(machines.get(m.id()), "spindle", "spindle.png", image, "engineer").id();

        var verification = predictions.verify(machines.get(m.id()), id);
        assertThat(verification.status()).isEqualTo(PredictionVerification.Status.VERIFIED);
        assertThat(verification.checks()).allSatisfy(c ->
                assertThat(c.outcome()).as(c.name() + ": " + c.detail()).isEqualTo(PredictionVerification.Outcome.PASS));

        var rows = jdbc.sql("""
                SELECT source_table, prediction_id, model_name, model_version, predicted_class, risk_score, input_reference,
                       trigger_type, prediction_timestamp
                FROM prediction_governance WHERE device_id = :d ORDER BY source_table""").param("d", m.id()).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("source_table")).containsExactly("inspections", "model_predictions");
        assertThat(rows).extracting(r -> ((Number) r.get("prediction_id")).longValue()).containsExactly(inspection, id);
        assertThat(rows).extracting(r -> r.get("model_name")).containsExactly("defect-detector", "failure-risk");
        assertThat(rows).allSatisfy(r -> assertThat(r.values()).doesNotContainNull());
        assertThat((String) rows.get(0).get("input_reference")).contains(" sha256:");
        assertThat((String) rows.get(1).get("input_reference")).contains("features=sha256:");

        // the governance fields are mandatory for every new prediction
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO model_predictions (device_id, predicted_at, trigger_type, probability, risk_level, readings_used,
                    model_name, input_reference, contributions_json)
                VALUES (:d, now(), 'SCHEDULED', 0.1, 'LOW', 12, 'failure-risk', 'x', '[]')""").param("d", m.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseConstraintsBackUpTheApplicationRules() {
        Machine m = machines.register(new MachineService.RegisterMachine("PG-02", "pg", MachineType.CNC_MILL, null), "test");
        // impossible physics cannot be stored even if application validation were bypassed
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO telemetry (device_id, recorded_at, temperature, vibration, rpm, torque, pressure, source)
                VALUES (:d, now(), 55, -1, 2400, 35, 6, 'EDGE')""").param("d", m.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // automated actors cannot confirm failures
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO failure_events (device_id, occurred_at, failure_mode, description, confirmed_by, confirmed_at)
                VALUES (:d, now(), 'BEARING', 'x', 'kiro-agent', now())""").param("d", m.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        Instant start = Instant.now().minus(Duration.ofMinutes(100)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(1), Duration.ofMinutes(100), 0);
        for (int i = 0; i < 100; i++) {
            telemetry.ingest(machines.get(m.id()), gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, start,
                    start.plus(Duration.ofMinutes(i))));
        }
        predictions.predict(machines.get(m.id()), PredictionService.Trigger.SCHEDULED, null);
        assertThat(predictions.recent(m.id(), 1)).hasSize(1);
        var view = investigations.investigate(m.id(), "q", "engineer");
        long rec = view.recommendations().getFirst().id();
        // four-eyes rule at the database level
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO approvals (recommendation_id, decision, proposed_by, decided_by, decided_at)
                VALUES (:r, 'APPROVED', 'bas-diagnostics', 'bas-diagnostics', now())""").param("r", rec).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        investigations.review(rec, true, "ok", "engineer2");
        // one decision per recommendation
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO approvals (recommendation_id, decision, proposed_by, decided_by, decided_at)
                VALUES (:r, 'REJECTED', 'bas-diagnostics', 'engineer3', now())""").param("r", rec).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The retention and row-limit SQL (id windows, OFFSET, NOT EXISTS) on PostgreSQL, with the limits at 1,000. */
    @Test
    void predictionRetentionAndRowLimitsOnPostgres() {
        Machine m = machines.register(new MachineService.RegisterMachine("PG-04", "pg", MachineType.CNC_MILL, null), "test");
        var caller = new MlPredictionLog.Caller(m.id(), "engineer");
        var batch = Collections.nCopies(600, new MlPredictionService.ProcessInput('L', 299.0, 309.0, 1300, 68.0, 230));
        ml.predictProcessFailure(batch, caller);
        List<Long> newest = ml.predictProcessFailure(batch, caller).stream()
                .map(MlPredictionService.ProcessPrediction::predictionId).toList();
        assertThat(count("ml_predictions")).isEqualTo(1000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ml_predictions WHERE id IN (:ids)").param("ids", newest)
                .query(Long.class).single()).isEqualTo(600);

        var window = ml.analyzeBearingVibration(new Random(4).doubles(1024, -0.1, 0.1).toArray(), caller).getFirst();
        var fault = jdbc.sql("SELECT risk_score, probabilities_json FROM ml_predictions WHERE id = :id")
                .param("id", window.conditionPredictionId()).query().singleRow();
        assertThat(fault.get("risk_score")).isNull();
        assertThat((String) fault.get("probabilities_json")).startsWith("{\"normal\":");

        jdbc.sql("UPDATE ml_predictions SET predicted_at = :at WHERE id = :id").param("id", newest.getFirst())
                .param("at", Text.utc(Instant.now().minus(Duration.ofDays(31)))).update();
        assertThat(mlLog.deleteOlderThan(Instant.now().minus(Duration.ofDays(30)))).isEqualTo(1);

        // failure-risk: one cited and ten expired rows first, then enough recent ones to pass the limit
        Instant old = Instant.now().minus(Duration.ofDays(100));
        insertPredictions(m.id(), old, 11);
        long cited = jdbc.sql("SELECT MIN(id) FROM model_predictions WHERE device_id = :d").param("d", m.id())
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO failure_events (device_id, occurred_at, failure_mode, description, confirmed_by, confirmed_at,
                    prior_prediction_id)
                VALUES (:d, now(), 'BEARING', 'Outer race spalling', 'engineer', now(), :p)""")
                .param("d", m.id()).param("p", cited).update();
        insertPredictions(m.id(), Instant.now().minus(Duration.ofDays(1)), 1_050);

        assertThat(predictions.deleteOlderThan(Instant.now().minus(Duration.ofDays(90)))).isEqualTo(10);
        long rows = count("model_predictions");
        assertThat(predictions.enforceRowLimit()).isEqualTo((int) (rows - 1000));
        assertThat(count("model_predictions")).isEqualTo(1000);
        assertThat(jdbc.sql("SELECT prior_prediction_id FROM failure_events WHERE device_id = :d").param("d", m.id())
                .query(Long.class).single()).isEqualTo(cited);
    }

    private void insertPredictions(java.util.UUID device, Instant from, int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new Object[] {device, Text.utc(from.plus(Duration.ofMinutes(i)))});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO model_predictions (device_id, predicted_at, trigger_type, probability, risk_level, readings_used,
                    model_name, model_version, input_reference, contributions_json)
                VALUES (?, ?, 'API', 0.2, 'LOW', 12, 'failure-risk', 'test', 'telemetry[test] n=12', '[]')""", rows);
    }

    /** Table names are constants of this test, never input. */
    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
