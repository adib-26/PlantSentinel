package com.basintelligence.analytics;

import java.util.ArrayList;
import java.util.List;

import com.basintelligence.common.Text;
import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.machine.OperatingLimits;
import com.basintelligence.machine.Severity;
import com.basintelligence.telemetry.TelemetryReading;

/**
 * Per-reading detection run at ingestion time. Two complementary checks:
 * <ul>
 * <li>Envelope: absolute operating limits of the machine type (catches slow drift into unsafe zones).</li>
 * <li>Z-score: deviation from the machine's own recent baseline (catches sudden spikes that are still
 * inside the envelope).</li>
 * </ul>
 * Stateless and side-effect free so it can be unit tested directly.
 */
public final class StreamingAnomalyDetector {

    /** Minimum baseline length before the z-score check is trusted. */
    static final int MIN_BASELINE = 20;

    private final double zThreshold;

    public StreamingAnomalyDetector(double zThreshold) {
        this.zThreshold = zThreshold;
    }

    public record Result(Severity envelopeSeverity, List<AnomalyEvent> events) {
    }

    public Result evaluate(MachineType type, TelemetryReading current, List<TelemetryReading> baseline) {
        List<AnomalyEvent> events = new ArrayList<>();
        Severity worst = Severity.NORMAL;
        for (Metric metric : Metric.values()) {
            double value = metric.of(current);
            OperatingLimits limits = type.limits(metric);
            Severity severity = limits.evaluate(value);
            worst = worst.max(severity);
            if (severity != Severity.NORMAL) {
                events.add(new AnomalyEvent(current.machineId(), current.recordedAt(), metric, AnomalyEvent.Detector.ENVELOPE,
                        severity, value, limits.nominal(),
                        "%s %s %s is outside the %s operating envelope (nominal %s)".formatted(
                                metric.label(), Text.num(value, 2), metric.unit(), severity.name().toLowerCase(),
                                Text.num(limits.nominal(), 2))));
            }
            if (baseline.size() >= MIN_BASELINE) {
                Stats stats = Stats.of(baseline.stream().mapToDouble(metric::of).toArray());
                // Floor the deviation at 1% of nominal so a perfectly flat signal does not explode the z-score.
                double sd = Math.max(stats.std(), 0.01 * limits.nominal());
                double z = (value - stats.mean()) / sd;
                if (Math.abs(z) >= zThreshold) {
                    events.add(new AnomalyEvent(current.machineId(), current.recordedAt(), metric, AnomalyEvent.Detector.ZSCORE,
                            Severity.WARNING, value, stats.mean(),
                            "%s %s %s deviates %s σ from its recent baseline (%s)".formatted(
                                    metric.label(), Text.num(value, 2), metric.unit(), Text.num(z, 1),
                                    Text.num(stats.mean(), 2))));
                }
            }
        }
        return new Result(worst, events);
    }
}
