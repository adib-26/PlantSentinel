package com.basintelligence.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.basintelligence.machine.Metric;

/**
 * Decides whether a raw edge reading can be trusted. A reading that fails is a <em>sensor fault</em> (a data
 * problem: missing channel, impossible physics), not a machine anomaly, so it is recorded separately and never fed
 * to the anomaly detectors or the risk model.
 */
public final class SensorPlausibility {

    public enum FaultType {
        MISSING_VALUE, IMPOSSIBLE_VALUE, STUCK_VALUE, INVALID_TIMESTAMP
    }

    public record Fault(FaultType type, String channel, String rawValue, String message) {
    }

    /** Physical limits per channel. Anything outside cannot be a real measurement from these machine types. */
    public record Bounds(Metric metric, double min, double max) {
    }

    public static final List<Bounds> BOUNDS = List.of(
            new Bounds(Metric.TEMPERATURE, -40, 400),
            new Bounds(Metric.VIBRATION, 0, 100),
            new Bounds(Metric.RPM, 0, 30_000),
            new Bounds(Metric.TORQUE, -2_000, 2_000),
            new Bounds(Metric.PRESSURE, 0, 100));

    private SensorPlausibility() {
    }

    /** Values in Metric order: temperature, vibration, rpm, torque, pressure. Null means the edge did not send it. */
    public static List<Fault> check(Double... values) {
        List<Fault> faults = new ArrayList<>();
        for (int i = 0; i < BOUNDS.size(); i++) {
            Bounds b = BOUNDS.get(i);
            Double v = i < values.length ? values[i] : null;
            String channel = b.metric().name();
            if (v == null) {
                faults.add(new Fault(FaultType.MISSING_VALUE, channel, null, b.metric().label() + " value is missing"));
            } else if (v.isNaN() || v.isInfinite() || v < b.min() || v > b.max()) {
                faults.add(new Fault(FaultType.IMPOSSIBLE_VALUE, channel, String.valueOf(v),
                        String.format(Locale.ROOT, "%s %s %s is outside the physical range %s to %s", b.metric().label(), v,
                                b.metric().unit(), fmt(b.min()), fmt(b.max()))));
            }
        }
        return faults;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
