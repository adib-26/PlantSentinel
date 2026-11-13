package com.basintelligence.machine;

import java.util.function.ToDoubleFunction;

import com.basintelligence.telemetry.TelemetryReading;

/** The five telemetry channels every BAS edge node reports. */
public enum Metric {
    TEMPERATURE("Temperature", "°C", TelemetryReading::temperature),
    VIBRATION("Vibration", "mm/s RMS", TelemetryReading::vibration),
    RPM("Speed", "rpm", TelemetryReading::rpm),
    TORQUE("Torque", "N·m", TelemetryReading::torque),
    PRESSURE("Pressure", "bar", TelemetryReading::pressure);

    private final String label;
    private final String unit;
    private final ToDoubleFunction<TelemetryReading> accessor;

    Metric(String label, String unit, ToDoubleFunction<TelemetryReading> accessor) {
        this.label = label;
        this.unit = unit;
        this.accessor = accessor;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }

    public double of(TelemetryReading reading) {
        return accessor.applyAsDouble(reading);
    }
}
