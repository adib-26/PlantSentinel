package com.basintelligence.machine;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the {@code device_types} and {@code device_type_limits} tables in step with {@link MachineType}.
 * The enum is the single source of truth for analytics; the tables make the categories and envelopes
 * queryable from SQL and referentially enforced for devices and documents.
 */
@Component
public class DeviceTypeCatalog {

    private final JdbcClient jdbc;

    public DeviceTypeCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One metric's operating envelope as stored in {@code device_type_limits}. */
    public record LimitRow(String metric, String unit, double nominal, Double warnLow, Double critLow, double warnHigh,
            double critHigh) {
    }

    public java.util.List<LimitRow> limits(MachineType type) {
        return jdbc.sql("SELECT * FROM device_type_limits WHERE device_type = :t ORDER BY metric")
                .param("t", type.name())
                .query((rs, n) -> new LimitRow(rs.getString("metric"), rs.getString("unit"), rs.getDouble("nominal"),
                        rs.getObject("warn_low", Double.class), rs.getObject("crit_low", Double.class),
                        rs.getDouble("warn_high"), rs.getDouble("crit_high")))
                .list();
    }

    @Transactional
    public void synchronise() {
        for (MachineType type : MachineType.values()) {
            boolean exists = jdbc.sql("SELECT COUNT(*) FROM device_types WHERE code = :code")
                    .param("code", type.name()).query(Long.class).single() > 0;
            jdbc.sql(exists
                    ? "UPDATE device_types SET display_name = :name WHERE code = :code"
                    : "INSERT INTO device_types (code, display_name) VALUES (:code, :name)")
                    .param("code", type.name()).param("name", type.displayName()).update();
            jdbc.sql("DELETE FROM device_type_limits WHERE device_type = :code").param("code", type.name()).update();
            for (Metric metric : Metric.values()) {
                OperatingLimits l = type.limits(metric);
                jdbc.sql("""
                        INSERT INTO device_type_limits
                            (device_type, metric, unit, nominal, warn_low, crit_low, warn_high, crit_high)
                        VALUES (:type, :metric, :unit, :nominal, :warnLow, :critLow, :warnHigh, :critHigh)""")
                        .param("type", type.name())
                        .param("metric", metric.name())
                        .param("unit", metric.unit())
                        .param("nominal", l.nominal())
                        .param("warnLow", Double.isNaN(l.warnLow()) ? null : l.warnLow())
                        .param("critLow", Double.isNaN(l.critLow()) ? null : l.critLow())
                        .param("warnHigh", l.warnHigh())
                        .param("critHigh", l.critHigh())
                        .update();
            }
        }
    }
}
