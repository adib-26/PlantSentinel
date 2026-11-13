package com.basintelligence.risk;

import java.util.List;

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
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("No readings");
        }
        return fromRatios(
                meanRatio(type, Metric.TEMPERATURE, readings),
                meanRatio(type, Metric.VIBRATION, readings),
                meanRatio(type, Metric.TORQUE, readings),
                meanRatio(type, Metric.PRESSURE, readings),
                meanRatio(type, Metric.RPM, readings));
    }

    private static double meanRatio(MachineType type, Metric metric, List<TelemetryReading> readings) {
        double nominal = type.limits(metric).nominal();
        return readings.stream().mapToDouble(metric::of).average().orElseThrow() / nominal;
    }
}
