package com.basintelligence.telemetry.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetrySource;

/**
 * Produces physically plausible telemetry for a machine type under a fault scenario.
 * Degradation progress is a pure function of time since {@code scenarioStart}, so restarts are seamless.
 */
public final class TelemetryGenerator {

    private final RandomGenerator random;
    private final Duration rampDuration;
    private final double spikeProbability;

    public TelemetryGenerator(RandomGenerator random, Duration rampDuration, double spikeProbability) {
        this.random = random;
        this.rampDuration = rampDuration;
        this.spikeProbability = spikeProbability;
    }

    public TelemetryReading generate(java.util.UUID machineId, MachineType type, Scenario scenario, Instant scenarioStart, Instant at) {
        double p = progress(scenarioStart, at);
        double temp = nominal(type, Metric.TEMPERATURE) * (1 + gaussian(0.015));
        double vib = nominal(type, Metric.VIBRATION) * (1 + gaussian(0.05));
        double rpm = nominal(type, Metric.RPM) * (1 + gaussian(0.004));
        double torque = nominal(type, Metric.TORQUE) * (1 + gaussian(0.03));
        double pressure = nominal(type, Metric.PRESSURE) * (1 + gaussian(0.012));

        switch (scenario) {
            case BEARING_WEAR -> {
                vib *= 1 + 3.2 * Math.pow(p, 1.6);
                temp *= 1 + 0.40 * Math.pow(p, 2.2);
                torque *= 1 + 0.06 * p;
            }
            case COOLING_DEGRADATION -> temp *= 1 + 0.45 * Math.pow(p, 1.4);
            case PRESSURE_LEAK -> {
                pressure *= 1 - 0.38 * Math.pow(p, 1.3);
                rpm *= 1 + 0.02 * p;
            }
            case OVERLOAD -> {
                torque *= 1 + 0.65 * Math.pow(p, 1.5);
                rpm *= 1 - 0.12 * p;
                temp *= 1 + 0.15 * p;
            }
            case HEALTHY -> {
                // nominal behaviour only
            }
        }
        // Rare transient spike (loose sensor, impact) so the statistical detector has something to catch.
        if (random.nextDouble() < spikeProbability) {
            vib *= 2.2;
        }
        return new TelemetryReading(machineId, at, round(temp, 2), round(Math.max(0, vib), 3), round(rpm, 1),
                round(torque, 2), round(Math.max(0, pressure), 3), TelemetrySource.SIMULATOR);
    }

    double progress(Instant start, Instant at) {
        double elapsed = Duration.between(start, at).toMillis();
        return Math.clamp(elapsed / rampDuration.toMillis(), 0.0, 1.0);
    }

    private double gaussian(double sd) {
        return random.nextGaussian() * sd;
    }

    private static double nominal(MachineType type, Metric metric) {
        return type.limits(metric).nominal();
    }

    private static double round(double value, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(value * f) / f;
    }
}
