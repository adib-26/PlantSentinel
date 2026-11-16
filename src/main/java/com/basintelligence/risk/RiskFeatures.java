package com.basintelligence.risk;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.telemetry.TelemetryReading;

/**
 * Machine-independent features. Each channel is first expressed as a ratio to the machine type's
 * nominal value, then turned into a one-sided "excess" so a healthy machine sits near zero on every axis.
 * This lets one model serve every machine type and keeps the logistic coefficients interpretable.
 */
public final class RiskFeatures {

    public static final List<String> NAMES = List.of(
            "temperature_excess", "vibration_excess", "torque_excess", "pressure_deficit", "speed_deviation", "power_excess");

    public static final List<String> LABELS = List.of(
            "Temperature above nominal", "Vibration above nominal", "Torque above nominal",
            "Pressure below nominal", "Speed deviation from nominal", "Mechanical power above nominal");

    private RiskFeatures() {
    }

    public static double[] fromRatios(double temp, double vib, double torque, double pressure, double speed) {
        return new double[] {
                Math.max(0, temp - 1),
                Math.max(0, vib - 1),
                Math.max(0, torque - 1),
                Math.max(0, 1 - pressure),
                Math.abs(speed - 1),
                Math.max(0, speed * torque - 1)
        };
    }

    /** Averages the readings (smoothing sensor noise) and converts them to features. */
    public static double[] fromReadings(MachineType type, List<TelemetryReading> readings) {
        return fromMeans(type, means(readings));
    }

    /** Mean of each channel over the readings: the model's input before it is scaled to features. */
    public static RiskAssessment.ChannelMeans means(List<TelemetryReading> readings) {
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("No readings");
        }
        return new RiskAssessment.ChannelMeans(
                mean(Metric.TEMPERATURE, readings),
                mean(Metric.VIBRATION, readings),
                mean(Metric.RPM, readings),
                mean(Metric.TORQUE, readings),
                mean(Metric.PRESSURE, readings));
    }

    /** Features from channel means, as ratios to the machine type's nominal values. */
    public static double[] fromMeans(MachineType type, RiskAssessment.ChannelMeans means) {
        return fromRatios(
                means.temperature() / type.limits(Metric.TEMPERATURE).nominal(),
                means.vibration() / type.limits(Metric.VIBRATION).nominal(),
                means.torque() / type.limits(Metric.TORQUE).nominal(),
                means.pressure() / type.limits(Metric.PRESSURE).nominal(),
                means.rpm() / type.limits(Metric.RPM).nominal());
    }

    /**
     * The feature vector in model order, read back from a prediction's contributions (which are sorted by effect).
     * Empty when a feature is missing or appears twice.
     */
    public static Optional<double[]> fromContributions(List<RiskAssessment.Contribution> contributions) {
        if (contributions == null || contributions.size() != NAMES.size()) {
            return Optional.empty();
        }
        double[] features = new double[NAMES.size()];
        boolean[] seen = new boolean[NAMES.size()];
        for (RiskAssessment.Contribution c : contributions) {
            int i = NAMES.indexOf(c.feature());
            if (i < 0 || seen[i]) {
                return Optional.empty();
            }
            seen[i] = true;
            features[i] = c.value();
        }
        return Optional.of(features);
    }

    private static double mean(Metric metric, List<TelemetryReading> readings) {
        return readings.stream().mapToDouble(metric::of).average().orElseThrow();
    }

    /** SHA-256 (hex) of the values as IEEE 754 doubles: identifies a feature vector or a set of model weights. */
    public static String sha256(double[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Double.BYTES);
        for (double v : values) {
            bytes.putDouble(v);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
