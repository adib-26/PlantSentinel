package com.basintelligence.telemetry;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.basintelligence.common.Text;

import io.micrometer.core.instrument.MeterRegistry;

/** Records sensor faults: readings that could not be trusted. Separate from anomalies, predictions and failures. */
@Service
public class SensorFaultService {

    public record SensorFault(long id, UUID machineId, Instant detectedAt, Instant readingTime, String gatewayId,
            String eventId, SensorPlausibility.FaultType type, String channel, String rawValue, String message) {
    }

    private static final RowMapper<SensorFault> MAPPER = (rs, n) -> new SensorFault(rs.getLong("id"),
            rs.getObject("device_id", UUID.class), instant(rs, "detected_at"), instant(rs, "reading_time"),
            rs.getString("gateway_id"), rs.getString("event_id"),
            SensorPlausibility.FaultType.valueOf(rs.getString("fault_type")), rs.getString("channel"),
            rs.getString("raw_value"), rs.getString("message"));

    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final Clock clock;

    public SensorFaultService(JdbcClient jdbc, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.clock = clock;
    }

    public void record(UUID machineId, Instant readingTime, String gatewayId, String eventId,
            SensorPlausibility.Fault fault) {
        jdbc.sql("""
                INSERT INTO sensor_faults (device_id, detected_at, reading_time, gateway_id, event_id, fault_type, channel,
                                           raw_value, message)
                VALUES (:d, :at, :rt, :g, :e, :t, :c, :raw, :msg)""")
                .param("d", machineId).param("at", utc(clock.instant())).param("rt", utc(readingTime))
                .param("g", Text.truncate(gatewayId, 80)).param("e", Text.truncate(eventId, 100))
                .param("t", fault.type().name()).param("c", fault.channel())
                .param("raw", Text.truncate(fault.rawValue(), 40)).param("msg", Text.truncate(fault.message(), 300))
                .update();
        meters.counter("bas.sensor.faults", "type", fault.type().name()).increment();
        meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_INVALID, "reason", fault.type().name()).increment();
    }

    public boolean existsSince(UUID machineId, SensorPlausibility.FaultType type, String channel, Instant since) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM sensor_faults WHERE device_id = :d AND fault_type = :t AND channel = :c
                AND detected_at >= :s""").param("d", machineId).param("t", type.name()).param("c", channel)
                .param("s", utc(since)).query(Long.class).single() > 0;
    }

    /** Newest first. */
    public List<SensorFault> recent(UUID machineId, Duration window, int limit) {
        return jdbc.sql("SELECT * FROM sensor_faults WHERE device_id = :d AND detected_at >= :s ORDER BY id DESC LIMIT :l")
                .param("d", machineId).param("s", utc(clock.instant().minus(window))).param("l", limit)
                .query(MAPPER).list();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM sensor_faults WHERE detected_at < :c").param("c", utc(cutoff)).update();
    }
}
