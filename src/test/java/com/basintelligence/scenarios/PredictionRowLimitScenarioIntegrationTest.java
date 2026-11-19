package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.basintelligence.common.ActivityTracker;
import com.basintelligence.common.BasMetrics;
import com.basintelligence.common.Text;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.ml.MlPredictionLog;
import com.basintelligence.ml.MlPredictionService;
import com.basintelligence.risk.PredictionService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * "Someone sends large ML batches often." The age limit alone would let the free-plan database fill up between two
 * hourly runs, so each prediction table also has a row limit: the oldest rows go first, and failure-risk rows that an
 * investigation or a confirmed failure cites stay. The limits here are the minimum, 1,000 rows. The context is
 * closed afterwards because its low limits would otherwise keep applying to the shared test database.
 */
@SpringBootTest(properties = {"bas.predictions.max-rows=1000", "bas.predictions.ml-max-rows=1000"})
@ActiveProfiles("test")
@DirtiesContext
class PredictionRowLimitScenarioIntegrationTest {

    @Autowired MlPredictionService ml;
    @Autowired PredictionService predictions;
    @Autowired MachineService machines;
    @Autowired ActivityTracker activity;
    @Autowired MeterRegistry meters;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void mlPredictionsNeverExceedTheRowLimitAndTheOldestGoFirst() {
        var caller = new MlPredictionLog.Caller(null, "engineer");
        var batch = Collections.nCopies(600, new MlPredictionService.ProcessInput('L', 299.0, 309.0, 1300, 68.0, 230));

        List<Long> first = ml.predictProcessFailure(batch, caller).stream()
                .map(MlPredictionService.ProcessPrediction::predictionId).sorted().toList();
        assertThat(count("ml_predictions")).isLessThanOrEqualTo(1000);
        List<Long> second = ml.predictProcessFailure(batch, caller).stream()
                .map(MlPredictionService.ProcessPrediction::predictionId).toList();

        assertThat(count("ml_predictions")).isEqualTo(1000);
        assertThat(existing("ml_predictions", second)).as("a call never removes its own rows").hasSize(600);
        assertThat(existing("ml_predictions", first.subList(0, 200))).as("the oldest 200 of the first call").isEmpty();
        assertThat(existing("ml_predictions", first.subList(200, 600))).hasSize(400);
        assertThat(removedOverLimit("ml_predictions")).isGreaterThanOrEqualTo(200);
    }

    @Test
    void failureRiskPredictionsBeyondTheRowLimitGoOldestFirstExceptCitedOnes() {
        Machine m = machines.register(new MachineService.RegisterMachine("LIM-01", "LIM-01", MachineType.CNC_MILL, null),
                "test");
        Instant start = Instant.now().minus(Duration.ofDays(2));
        insertPredictions(m.id(), start, 1);
        long cited = ids(m.id()).getFirst();
        jdbc.sql("""
                INSERT INTO investigations (device_id, prediction_id, status, requested_by, created_at)
                VALUES (:d, :p, 'OPEN', 'engineer', CURRENT_TIMESTAMP)""").param("d", m.id()).param("p", cited).update();
        insertPredictions(m.id(), start.plusSeconds(60), 1_100); // more than the limit on their own
        List<Long> bulk = ids(m.id()).subList(1, 1_101);
        // the database is shared with other test classes, whose cited predictions also stay
        long citedAnywhere = jdbc.sql("""
                SELECT COUNT(*) FROM model_predictions p
                WHERE EXISTS (SELECT 1 FROM investigations i WHERE i.prediction_id = p.id)
                   OR EXISTS (SELECT 1 FROM failure_events f WHERE f.prior_prediction_id = p.id)""").query(Long.class).single();

        activity.touch(); // the job skips its run while nobody uses the app
        predictions.applyRetention();

        assertThat(count("model_predictions")).isEqualTo(1000);
        assertThat(existing("model_predictions", List.of(cited))).as("cited by an investigation, and the oldest").hasSize(1);
        int kept = (int) (1000 - citedAnywhere); // every other row left is one of the newest bulk rows
        assertThat(existing("model_predictions", bulk)).containsExactlyElementsOf(bulk.subList(bulk.size() - kept, bulk.size()));
        assertThat(removedOverLimit("model_predictions")).isGreaterThanOrEqualTo(1_100 - kept);
    }

    /** Rows one minute apart, as the API trigger would write them. */
    private void insertPredictions(UUID device, Instant from, int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new Object[] {device, Text.utc(from.plus(Duration.ofMinutes(i)))});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO model_predictions (device_id, predicted_at, trigger_type, probability, risk_level, readings_used,
                    model_name, model_version, input_reference, contributions_json)
                VALUES (?, ?, 'API', 0.2, 'LOW', 12, 'failure-risk', 'test', 'telemetry[test] n=12', '[]')""", rows);
    }

    private List<Long> ids(UUID device) {
        return jdbc.sql("SELECT id FROM model_predictions WHERE device_id = :d ORDER BY id").param("d", device)
                .query(Long.class).list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    /** Table names are constants of this test, never input. */
    private List<Long> existing(String table, List<Long> ids) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE id IN (:ids) ORDER BY id").param("ids", ids)
                .query(Long.class).list();
    }

    private double removedOverLimit(String table) {
        Counter c = meters.find(BasMetrics.PREDICTIONS_REMOVED).tag("table", table).tag("reason", "row_limit").counter();
        return c == null ? 0 : c.count();
    }
}
