package com.basintelligence.ml;

import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.basintelligence.common.BasMetrics;
import com.basintelligence.common.IdWindows;
import com.basintelligence.config.BasProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stores every prediction made through the ML-lab endpoints in {@code ml_predictions}: one row per scored item and
 * model, all rows of one call under one request id. They appear in the {@code prediction_governance} view. The table
 * never holds more than {@code bas.predictions.ml-max-rows} rows, and rows older than
 * {@code bas.predictions.ml-retention} are deleted by the hourly job ({@link MlPredictionService#applyRetention}).
 */
@Repository
public class MlPredictionLog {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionLog.class);
    private static final String TABLE = "ml_predictions";
    private static final Duration WARN_EVERY = Duration.ofHours(1);

    /** Who asked, and for which machine. {@code machineId} is null when the caller named none. */
    public record Caller(UUID machineId, String username) {
    }

    /**
     * One prediction. {@code item} is the batch row or window index. {@code riskScore} is the model's 0-1 score for
     * the bad outcome, from which {@code predictedClass} follows; null when the model has none (the RUL regressor
     * and the bearing fault classifier). {@code predictedValue} is a regression's output and {@code probabilities}
     * a multi-class model's class probabilities in class order; both null for other models.
     */
    public record Entry(int item, String modelName, String modelVersion, String predictedClass, Double riskScore,
            Double predictedValue, Map<String, Double> probabilities, String inputReference) {
    }

    private record Ids(Long first, Long last) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final BasProperties.Predictions limits;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicReference<Instant> lastLimitWarning = new AtomicReference<>(Instant.EPOCH);

    public MlPredictionLog(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper json, BasProperties properties, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.limits = properties.predictions();
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Inserts all entries in one transaction and returns their ids, in the order given. Then removes the oldest rows
     * beyond the row limit. That second step cannot fail the call, because the predictions are already stored; the
     * hourly job repeats it.
     */
    public List<Long> record(Caller caller, Instant predictedAt, List<Entry> entries) {
        List<Long> ids = transactions.execute(status -> insert(caller, predictedAt, entries));
        try {
            enforceRowLimit();
        } catch (DataAccessException e) {
            log.warn("Could not apply the {} row limit; the hourly job will retry: {}", TABLE, e.getMessage());
        }
        return ids;
    }

    /** Deletes the rows predicted before {@code cutoff} and returns how many. */
    public int deleteOlderThan(Instant cutoff) {
        Map<String, Object> params = Map.of("cutoff", utc(cutoff));
        Ids old = ids("SELECT MIN(id) AS first_id, MAX(id) AS last_id FROM ml_predictions WHERE predicted_at < :cutoff",
                params);
        int removed = IdWindows.delete(old.first(), old.last(), (from, to) -> jdbc.update("""
                DELETE FROM ml_predictions WHERE id BETWEEN :from AND :to AND predicted_at < :cutoff""",
                Map.of("from", from, "to", to, "cutoff", utc(cutoff))));
        if (removed > 0) {
            meters.counter(BasMetrics.PREDICTIONS_REMOVED, "table", TABLE, "reason", "retention").increment(removed);
            log.info("Retention removed {} {} rows older than {}", removed, TABLE, limits.mlRetention());
        }
        return removed;
    }

    /** Deletes the oldest rows beyond {@code bas.predictions.ml-max-rows} and returns how many. */
    public int enforceRowLimit() {
        int max = limits.mlMaxRows();
        Ids all = ids("SELECT MIN(id) AS first_id, MAX(id) AS last_id FROM ml_predictions", Map.of());
        // ids only grow and are never reused, so the row count is at most the id span: one index lookup per call
        if (all.first() == null || all.last() - all.first() + 1 <= max) {
            return 0;
        }
        List<Long> newestOverLimit = jdbc.queryForList("SELECT id FROM ml_predictions ORDER BY id DESC LIMIT 1 OFFSET :max",
                Map.of("max", max), Long.class);
        if (newestOverLimit.isEmpty()) {
            return 0;
        }
        int removed = IdWindows.delete(all.first(), newestOverLimit.getFirst(), (from, to) -> jdbc.update(
                "DELETE FROM ml_predictions WHERE id BETWEEN :from AND :to", Map.of("from", from, "to", to)));
        if (removed > 0) {
            meters.counter(BasMetrics.PREDICTIONS_REMOVED, "table", TABLE, "reason", "row_limit").increment(removed);
            warnAtMostHourly(removed, max);
        }
        return removed;
    }

    private List<Long> insert(Caller caller, Instant predictedAt, List<Entry> entries) {
        UUID request = UUID.randomUUID();
        SqlParameterSource[] rows = entries.stream().map(e -> new MapSqlParameterSource()
                .addValue("request", request)
                .addValue("item", e.item())
                .addValue("device", caller.machineId())
                .addValue("at", utc(predictedAt))
                .addValue("model", e.modelName())
                .addValue("version", e.modelVersion())
                .addValue("class", e.predictedClass())
                .addValue("score", finiteOrNull(e.riskScore()))
                .addValue("value", finiteOrNull(e.predictedValue()))
                .addValue("probabilities", toJson(e.probabilities()))
                .addValue("input", e.inputReference())
                .addValue("by", caller.username()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO ml_predictions (request_id, item_index, device_id, predicted_at, model_name, model_version,
                    predicted_class, risk_score, predicted_value, probabilities_json, input_reference, requested_by)
                VALUES (:request, :item, :device, :at, :model, :version, :class, :score, :value, :probabilities, :input,
                    :by)""", rows);

        // a batch insert does not return generated keys portably, so read them back by request id
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, model_name, item_index FROM ml_predictions WHERE request_id = :request",
                Map.of("request", request),
                rs -> {
                    ids.put(key(rs.getString("model_name"), rs.getInt("item_index")), rs.getLong("id"));
                });
        return entries.stream().map(e -> ids.get(key(e.modelName(), e.item()))).toList();
    }

    private Ids ids(String sql, Map<String, ?> params) {
        return jdbc.queryForObject(sql, params,
                (rs, n) -> new Ids(rs.getObject("first_id", Long.class), rs.getObject("last_id", Long.class)));
    }

    /** Reaching the limit means rows went before their retention period ended: worth one warning, not one per call. */
    private void warnAtMostHourly(int removed, int max) {
        Instant now = clock.instant();
        Instant last = lastLimitWarning.get();
        if (!now.isBefore(last.plus(WARN_EVERY)) && lastLimitWarning.compareAndSet(last, now)) {
            log.warn("{} reached its limit of {} rows and removed the oldest {} before their {} retention ended. Raise "
                    + "bas.predictions.ml-max-rows or send fewer rows; metric {} counts every removal.", TABLE, max,
                    removed, limits.mlRetention(), BasMetrics.PREDICTIONS_REMOVED);
        }
    }

    private String toJson(Map<String, Double> probabilities) {
        try {
            return probabilities == null ? null : json.writeValueAsString(probabilities);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String model, int item) {
        return model + "#" + item;
    }

    private static Double finiteOrNull(Double value) {
        return value == null || !Double.isFinite(value) ? null : value;
    }
}
