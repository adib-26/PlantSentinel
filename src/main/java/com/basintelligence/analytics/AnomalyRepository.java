package com.basintelligence.analytics;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.basintelligence.machine.Metric;
import com.basintelligence.machine.Severity;

@Repository
public class AnomalyRepository {

    private static final RowMapper<AnomalyEvent> MAPPER = (rs, n) -> new AnomalyEvent(
            rs.getObject("device_id", UUID.class),
            instant(rs, "detected_at"),
            Metric.valueOf(rs.getString("metric")),
            AnomalyEvent.Detector.valueOf(rs.getString("detector")),
            Severity.valueOf(rs.getString("severity")),
            rs.getDouble("observed_value"),
            (Double) rs.getObject("expected_value"),
            rs.getString("message"));

    private final JdbcClient jdbc;

    public AnomalyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(AnomalyEvent e) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO anomaly_events (device_id, detected_at, metric, detector, severity, observed_value, expected_value, message)
                VALUES (:deviceId, :at, :metric, :detector, :severity, :observed, :expected, :message)""")
                .param("deviceId", e.machineId())
                .param("at", utc(e.detectedAt()))
                .param("metric", e.metric().name())
                .param("detector", e.detector().name())
                .param("severity", e.severity().name())
                .param("observed", e.observedValue())
                .param("expected", e.expectedValue())
                .param("message", e.message())
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    /** True if an event of the same kind and at least the same severity was raised since {@code since}. */
    public boolean existsSince(UUID deviceId, Metric metric, AnomalyEvent.Detector detector, Severity severity, Instant since) {
        List<String> severities = severity == Severity.CRITICAL ? List.of("CRITICAL") : List.of("WARNING", "CRITICAL");
        return jdbc.sql("""
                SELECT COUNT(*) FROM anomaly_events
                WHERE device_id = :deviceId AND metric = :metric AND detector = :detector
                  AND severity IN (:severities) AND detected_at >= :since""")
                .param("deviceId", deviceId)
                .param("metric", metric.name())
                .param("detector", detector.name())
                .param("severities", severities)
                .param("since", utc(since))
                .query(Long.class).single() > 0;
    }

    /** Newest first. */
    public List<AnomalyEvent> findSince(UUID deviceId, Instant since, int limit) {
        return jdbc.sql("""
                SELECT * FROM anomaly_events WHERE device_id = :deviceId AND detected_at >= :since
                ORDER BY detected_at DESC LIMIT :limit""")
                .param("deviceId", deviceId)
                .param("since", utc(since))
                .param("limit", limit)
                .query(MAPPER).list();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM anomaly_events WHERE detected_at < :cutoff").param("cutoff", utc(cutoff)).update();
    }
}
