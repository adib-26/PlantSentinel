package com.basintelligence.telemetry;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.ActivityTracker;
import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineRepository;

/**
 * Aggregates raw telemetry into 5-minute buckets (mean plus the worst-case extreme per channel). Raw readings
 * are kept for a few days; rollups are kept for months and feed the long-range dashboard charts. Aggregation is
 * done in Java so the SQL stays portable between PostgreSQL and H2.
 */
@Service
public class TelemetryRollupService {

    public static final Duration BUCKET = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(TelemetryRollupService.class);

    public record Rollup(Instant bucketStart, int samples, double temperatureAvg, double temperatureMax,
            double vibrationAvg, double vibrationMax, double rpmAvg, double rpmMin, double torqueAvg, double torqueMax,
            double pressureAvg, double pressureMin) {
    }

    private final JdbcClient jdbc;
    private final TelemetryRepository telemetry;
    private final MachineRepository machines;
    private final ActivityTracker activity;
    private final BasProperties properties;
    private final Clock clock;

    public TelemetryRollupService(JdbcClient jdbc, TelemetryRepository telemetry, MachineRepository machines,
            ActivityTracker activity, BasProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.telemetry = telemetry;
        this.machines = machines;
        this.activity = activity;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5M")
    public void scheduledRollup() {
        if (!activity.isIdle()) {
            rollupAll();
        }
    }

    /** Rolls up every completed bucket that has not been rolled up yet. Idempotent. */
    public int rollupAll() {
        int written = 0;
        for (Machine m : machines.findAll()) {
            written += rollup(m.id());
        }
        if (written > 0) {
            log.debug("Wrote {} telemetry rollup buckets", written);
        }
        return written;
    }

    @Transactional
    public int rollup(UUID deviceId) {
        // MAX() returns NULL when nothing has been rolled up yet; list().get(0) tolerates that.
        Instant last = jdbc.sql("SELECT MAX(bucket_start) FROM telemetry_rollup_5m WHERE device_id = :d")
                .param("d", deviceId).query((rs, n) -> instant(rs, 1)).list().get(0);
        Instant from = last == null ? telemetry.earliest(deviceId) : last.plus(BUCKET);
        if (from == null) {
            return 0;
        }
        from = floor(from);
        Instant to = floor(clock.instant()); // only completed buckets
        if (!from.isBefore(to)) {
            return 0;
        }
        List<Rollup> rollups = aggregate(telemetry.findBetween(deviceId, from, to));
        for (Rollup r : rollups) {
            jdbc.sql("""
                    INSERT INTO telemetry_rollup_5m (device_id, bucket_start, sample_count, temperature_avg, temperature_max,
                        vibration_avg, vibration_max, rpm_avg, rpm_min, torque_avg, torque_max, pressure_avg, pressure_min)
                    VALUES (:d, :b, :n, :ta, :tm, :va, :vm, :ra, :rm, :qa, :qm, :pa, :pm)""")
                    .param("d", deviceId).param("b", utc(r.bucketStart())).param("n", r.samples())
                    .param("ta", r.temperatureAvg()).param("tm", r.temperatureMax())
                    .param("va", r.vibrationAvg()).param("vm", r.vibrationMax())
                    .param("ra", r.rpmAvg()).param("rm", r.rpmMin())
                    .param("qa", r.torqueAvg()).param("qm", r.torqueMax())
                    .param("pa", r.pressureAvg()).param("pm", r.pressureMin())
                    .update();
        }
        return rollups.size();
    }

    /** Rollups in chronological order. */
    public List<Rollup> since(UUID deviceId, Instant since) {
        return jdbc.sql("SELECT * FROM telemetry_rollup_5m WHERE device_id = :d AND bucket_start >= :s ORDER BY bucket_start")
                .param("d", deviceId).param("s", utc(since))
                .query((rs, n) -> new Rollup(instant(rs, "bucket_start"), rs.getInt("sample_count"),
                        rs.getDouble("temperature_avg"), rs.getDouble("temperature_max"), rs.getDouble("vibration_avg"),
                        rs.getDouble("vibration_max"), rs.getDouble("rpm_avg"), rs.getDouble("rpm_min"),
                        rs.getDouble("torque_avg"), rs.getDouble("torque_max"), rs.getDouble("pressure_avg"),
                        rs.getDouble("pressure_min")))
                .list();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM telemetry_rollup_5m WHERE bucket_start < :c").param("c", utc(cutoff)).update();
    }

    static List<Rollup> aggregate(List<TelemetryReading> readings) {
        Map<Instant, List<TelemetryReading>> buckets = new TreeMap<>();
        for (TelemetryReading r : readings) {
            buckets.computeIfAbsent(floor(r.recordedAt()), k -> new ArrayList<>()).add(r);
        }
        List<Rollup> out = new ArrayList<>();
        buckets.forEach((start, rs) -> out.add(new Rollup(start, rs.size(),
                rs.stream().mapToDouble(TelemetryReading::temperature).average().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::temperature).max().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::vibration).average().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::vibration).max().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::rpm).average().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::rpm).min().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::torque).average().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::torque).max().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::pressure).average().orElseThrow(),
                rs.stream().mapToDouble(TelemetryReading::pressure).min().orElseThrow())));
        return out;
    }

    static Instant floor(Instant t) {
        long size = BUCKET.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(t.toEpochMilli(), size) * size);
    }

    Duration rollupRetention() {
        return properties.telemetry().rollupRetention();
    }
}
