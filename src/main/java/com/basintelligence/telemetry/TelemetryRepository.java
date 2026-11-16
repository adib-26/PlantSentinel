package com.basintelligence.telemetry;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryRepository {

    private static final RowMapper<TelemetryReading> MAPPER = (rs, n) -> new TelemetryReading(
            rs.getObject("device_id", UUID.class),
            instant(rs, "recorded_at"),
            rs.getDouble("temperature"),
            rs.getDouble("vibration"),
            rs.getDouble("rpm"),
            rs.getDouble("torque"),
            rs.getDouble("pressure"),
            TelemetrySource.valueOf(rs.getString("source")));

    private final JdbcClient jdbc;

    public TelemetryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TelemetryReading r) {
        insert(r, null);
    }

    public boolean existsEvent(UUID deviceId, String eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM telemetry WHERE device_id = :d AND event_id = :e")
                .param("d", deviceId).param("e", eventId).query(Long.class).single() > 0;
    }

    /** {@code eventId} may be null; a duplicate (device, event id) violates a unique index. */
    public void insert(TelemetryReading r, String eventId) {
        jdbc.sql("""
                INSERT INTO telemetry (device_id, recorded_at, temperature, vibration, rpm, torque, pressure, source, event_id)
                VALUES (:deviceId, :at, :temperature, :vibration, :rpm, :torque, :pressure, :source, :eventId)""")
                .param("eventId", eventId)
                .param("deviceId", r.machineId())
                .param("at", utc(r.recordedAt()))
                .param("temperature", r.temperature())
                .param("vibration", r.vibration())
                .param("rpm", r.rpm())
                .param("torque", r.torque())
                .param("pressure", r.pressure())
                .param("source", r.source().name())
                .update();
    }

    /** Readings in chronological order. */
    public List<TelemetryReading> findBetween(UUID deviceId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT * FROM telemetry
                WHERE device_id = :deviceId AND recorded_at >= :from AND recorded_at < :to
                ORDER BY recorded_at""")
                .param("deviceId", deviceId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(MAPPER)
                .list();
    }

    public List<TelemetryReading> findSince(UUID deviceId, Instant since) {
        return findBetween(deviceId, since, Instant.parse("9999-12-31T00:00:00Z"));
    }

    /** At most {@code limit} readings with {@code from <= recorded_at <= to}, in chronological order. */
    public List<TelemetryReading> findWithin(UUID deviceId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                SELECT * FROM telemetry
                WHERE device_id = :deviceId AND recorded_at >= :from AND recorded_at <= :to
                ORDER BY recorded_at LIMIT :limit""")
                .param("deviceId", deviceId)
                .param("from", utc(from))
                .param("to", utc(to))
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** The {@code limit} readings strictly before {@code before}, in chronological order. */
    public List<TelemetryReading> findLatestBefore(UUID deviceId, Instant before, int limit) {
        List<TelemetryReading> rows = new ArrayList<>(jdbc.sql("""
                SELECT * FROM telemetry
                WHERE device_id = :deviceId AND recorded_at < :before
                ORDER BY recorded_at DESC LIMIT :limit""")
                .param("deviceId", deviceId)
                .param("before", utc(before))
                .param("limit", limit)
                .query(MAPPER)
                .list());
        Collections.reverse(rows);
        return rows;
    }

    public List<TelemetryReading> findLatest(UUID deviceId, int limit) {
        return findLatestBefore(deviceId, Instant.parse("9999-12-31T00:00:00Z"), limit);
    }

    public Instant earliest(UUID deviceId) {
        return jdbc.sql("SELECT MIN(recorded_at) FROM telemetry WHERE device_id = :d").param("d", deviceId)
                .query((rs, n) -> instant(rs, 1)).list().get(0); // NULL when empty
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM telemetry WHERE recorded_at < :cutoff").param("cutoff", utc(cutoff)).update();
    }
}
