package com.basintelligence.telemetry;

import java.time.Instant;
import java.util.UUID;

/** One multi-channel sample from a machine. Units: °C, mm/s RMS, rpm, N·m, bar. */
public record TelemetryReading(
        UUID machineId,
        Instant recordedAt,
        double temperature,
        double vibration,
        double rpm,
        double torque,
        double pressure,
        TelemetrySource source) {
}
