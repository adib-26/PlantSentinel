package com.basintelligence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;

/** Deterministic telemetry fixtures for tests. */
public final class TestReadings {

    public static final java.util.UUID MACHINE = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private TestReadings() {
    }

    /** {@code count} readings one minute apart; the scenario ramps from start to finish of the series. */
    public static List<TelemetryReading> series(MachineType type, Scenario scenario, int count, long seed) {
        TelemetryGenerator generator = new TelemetryGenerator(new Random(seed), Duration.ofMinutes(count), 0);
        List<TelemetryReading> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(generator.generate(MACHINE, type, scenario, T0, T0.plus(Duration.ofMinutes(i))));
        }
        return out;
    }

    public static TelemetryReading reading(double temperature, double vibration, double rpm, double torque, double pressure) {
        return new TelemetryReading(MACHINE, T0, temperature, vibration, rpm, torque, pressure,
                com.basintelligence.telemetry.TelemetrySource.EDGE);
    }
}
