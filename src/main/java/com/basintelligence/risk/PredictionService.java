package com.basintelligence.risk;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.basintelligence.analytics.CriticalAnomalyDetected;
import com.basintelligence.common.ActivityTracker;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persists failure-risk predictions to {@code model_predictions}: on a schedule (risk history), when a critical
 * anomaly occurs (linked to that anomaly event), and for every investigation.
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    public enum Trigger {
        SCHEDULED, ANOMALY, INVESTIGATION, AGENT
    }

    public record Prediction(long id, UUID machineId, Long anomalyEventId, Instant predictedAt, Trigger trigger,
            double probability, RiskLevel level, int readingsUsed, String modelName) {
    }

    private final JdbcClient jdbc;
    private final RiskService risk;
    private final MachineService machines;
    private final ActivityTracker activity;
    private final ObjectMapper json;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public PredictionService(JdbcClient jdbc, RiskService risk, MachineService machines, ActivityTracker activity,
            ObjectMapper json, Clock clock, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.jdbc = jdbc;
        this.risk = risk;
        this.machines = machines;
        this.activity = activity;
        this.json = json;
        this.clock = clock;
    }

    /** Assesses and stores. Empty when the machine has no telemetry yet. */
    public Optional<Long> predict(Machine machine, Trigger trigger, Long anomalyEventId) {
        return risk.assess(machine).map(a -> record(machine, a, trigger, anomalyEventId));
    }

    public long record(Machine machine, RiskAssessment a, Trigger trigger, Long anomalyEventId) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO model_predictions (device_id, anomaly_event_id, predicted_at, trigger_type, probability,
                    risk_level, readings_used, model_name, model_auc, contributions_json)
                VALUES (:d, :a, :at, :trigger, :p, :level, :n, :model, :auc, :contributions)""")
                .param("d", machine.id())
                .param("a", anomalyEventId)
                .param("at", utc(a.assessedAt()))
                .param("trigger", trigger.name())
                .param("p", a.probability())
                .param("level", a.level().name())
                .param("n", a.readingsUsed())
                .param("model", a.model().algorithm())
                .param("auc", a.model().holdoutAuc())
                .param("contributions", toJson(a.contributions()))
                .update(keys, "id");
        com.basintelligence.common.BasMetrics.recordPrediction(meters, RiskService.MODEL_NAME, a.level().name(), a.probability());
        return keys.getKey().longValue();
    }

    @EventListener
    public void onCriticalAnomaly(CriticalAnomalyDetected event) {
        predict(event.machine(), Trigger.ANOMALY, event.anomalyEventId());
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${bas.risk.prediction-interval:15m}")
    public void scheduled() {
        if (activity.isIdle()) {
            return;
        }
        for (Machine m : machines.list()) {
            try {
                predict(m, Trigger.SCHEDULED, null);
            } catch (RuntimeException e) {
                log.warn("Scheduled prediction failed for {}: {}", m.code(), e.getMessage());
            }
        }
    }

    /** Newest first. */
    public List<Prediction> recent(UUID machineId, int limit) {
        return jdbc.sql("SELECT * FROM model_predictions WHERE device_id = :d ORDER BY predicted_at DESC, id DESC LIMIT :l")
                .param("d", machineId).param("l", limit)
                .query((rs, n) -> new Prediction(rs.getLong("id"), rs.getObject("device_id", UUID.class),
                        (Long) rs.getObject("anomaly_event_id", Long.class), instant(rs, "predicted_at"),
                        Trigger.valueOf(rs.getString("trigger_type")), rs.getDouble("probability"),
                        RiskLevel.valueOf(rs.getString("risk_level")), rs.getInt("readings_used"),
                        rs.getString("model_name")))
                .list();
    }

    public Instant now() {
        return clock.instant();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
